package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.ChunkStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PostgresChunkStore implements ChunkStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresChunkStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("chunks");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(ChunkRecord chunk) {
        var record = Objects.requireNonNull(chunk, "chunk");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (workspace_id, id, document_id, text, token_count, chunk_order, metadata)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (workspace_id, id) DO UPDATE
                SET document_id = EXCLUDED.document_id,
                    text = EXCLUDED.text,
                    token_count = EXCLUDED.token_count,
                    chunk_order = EXCLUDED.chunk_order,
                    metadata = EXCLUDED.metadata
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.id());
                statement.setString(3, record.documentId());
                statement.setString(4, record.text());
                statement.setInt(5, record.tokenCount());
                statement.setInt(6, record.order());
                statement.setString(7, JdbcJsonCodec.writeStringMap(record.metadata()));
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<ChunkRecord> load(String chunkId) {
        var id = Objects.requireNonNull(chunkId, "chunkId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, document_id, text, token_count, chunk_order, metadata
                FROM %s
                WHERE workspace_id = ?
                  AND id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readChunk(resultSet));
                }
            }
        });
    }

    @Override
    public Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
        var ids = List.copyOf(Objects.requireNonNull(chunkIds, "chunkIds"));
        if (ids.isEmpty()) {
            return Map.of();
        }
        var uniqueIds = new LinkedHashSet<>(ids);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, document_id, text, token_count, chunk_order, metadata
                FROM %s
                WHERE workspace_id = ?
                  AND id IN (%s)
                """.formatted(tableName, placeholders(uniqueIds.size()))
            )) {
                statement.setString(1, workspaceId);
                int parameterIndex = 2;
                for (var id : uniqueIds) {
                    statement.setString(parameterIndex++, id);
                }
                try (var resultSet = statement.executeQuery()) {
                    var loaded = new LinkedHashMap<String, ChunkRecord>();
                    while (resultSet.next()) {
                        var chunk = readChunk(resultSet);
                        loaded.put(chunk.id(), chunk);
                    }
                    var ordered = new LinkedHashMap<String, ChunkRecord>();
                    for (var id : uniqueIds) {
                        var chunk = loaded.get(id);
                        if (chunk != null) {
                            ordered.put(id, chunk);
                        }
                    }
                    return Collections.unmodifiableMap(ordered);
                }
            }
        });
    }

    @Override
    public List<ChunkRecord> list() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, document_id, text, token_count, chunk_order, metadata
                FROM %s
                WHERE workspace_id = ?
                ORDER BY id
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var chunks = new java.util.ArrayList<ChunkRecord>();
                    while (resultSet.next()) {
                        chunks.add(readChunk(resultSet));
                    }
                    return List.copyOf(chunks);
                }
            }
        });
    }

    @Override
    public List<ChunkRecord> listByDocument(String documentId) {
        var id = Objects.requireNonNull(documentId, "documentId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, document_id, text, token_count, chunk_order, metadata
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                ORDER BY chunk_order, id
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    var chunks = new java.util.ArrayList<ChunkRecord>();
                    while (resultSet.next()) {
                        chunks.add(readChunk(resultSet));
                    }
                    return List.copyOf(chunks);
                }
            }
        });
    }

    private static ChunkRecord readChunk(ResultSet resultSet) throws SQLException {
        return new ChunkRecord(
            resultSet.getString("id"),
            resultSet.getString("document_id"),
            resultSet.getString("text"),
            resultSet.getInt("token_count"),
            resultSet.getInt("chunk_order"),
            JdbcJsonCodec.readStringMap(resultSet.getString("metadata"))
        );
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(index -> "?")
            .collect(java.util.stream.Collectors.joining(", "));
    }
}
