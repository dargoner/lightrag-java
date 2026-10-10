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
    private final List<String> workspaceIds;
    private final String workspaceId;

    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, List.of(workspaceId));
    }

    /**
     * Workspace-set constructor: point reads batch every workspace into one IN-filtered statement
     * and resolve colliding chunk ids to the smallest workspace id; writes and per-workspace
     * listings require a single workspace and fail loudly on a multi-workspace store.
     */
    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config, List<String> workspaceIds) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceIds);
    }

    PostgresChunkStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this(connectionAccess, config, List.of(workspaceId));
    }

    PostgresChunkStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, List<String> workspaceIds) {
        var normalized = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(workspaceIds, "workspaceIds")));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("workspaceIds must not be empty");
        }
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("chunks");
        this.workspaceIds = normalized;
        this.workspaceId = normalized.get(0);
    }

    private boolean isMultiWorkspace() {
        return workspaceIds.size() > 1;
    }

    private void requireSingleWorkspace(String operation) {
        if (isMultiWorkspace()) {
            throw new IllegalStateException(
                operation + " requires a single-workspace chunk store; this store covers "
                    + workspaceIds.size() + " workspaces");
        }
    }

    @Override
    public void save(ChunkRecord chunk) {
        requireSingleWorkspace("save");
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
        if (isMultiWorkspace()) {
            return connectionAccess.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                    """
                    SELECT id, document_id, text, token_count, chunk_order, metadata
                    FROM %s
                    WHERE workspace_id IN (%s)
                      AND id = ?
                    ORDER BY workspace_id
                    LIMIT 1
                    """.formatted(tableName, placeholders(workspaceIds.size()))
                )) {
                    int parameterIndex = 1;
                    for (var workspace : workspaceIds) {
                        statement.setString(parameterIndex++, workspace);
                    }
                    statement.setString(parameterIndex, id);
                    try (var resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(readChunk(resultSet));
                    }
                }
            });
        }
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
        if (isMultiWorkspace()) {
            return connectionAccess.withConnection(connection -> {
                try (var statement = connection.prepareStatement(
                    """
                    SELECT id, document_id, text, token_count, chunk_order, metadata
                    FROM %s
                    WHERE workspace_id IN (%s)
                      AND id IN (%s)
                    ORDER BY id, workspace_id
                    """.formatted(
                        tableName,
                        placeholders(workspaceIds.size()),
                        placeholders(uniqueIds.size()))
                )) {
                    int parameterIndex = 1;
                    for (var workspace : workspaceIds) {
                        statement.setString(parameterIndex++, workspace);
                    }
                    for (var id : uniqueIds) {
                        statement.setString(parameterIndex++, id);
                    }
                    try (var resultSet = statement.executeQuery()) {
                        var loaded = new LinkedHashMap<String, ChunkRecord>();
                        while (resultSet.next()) {
                            var chunk = readChunk(resultSet);
                            // Rows arrive ordered by id then workspace id: the smallest workspace
                            // wins for colliding chunk ids.
                            loaded.putIfAbsent(chunk.id(), chunk);
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
        requireSingleWorkspace("list");
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
        requireSingleWorkspace("listByDocument");
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
