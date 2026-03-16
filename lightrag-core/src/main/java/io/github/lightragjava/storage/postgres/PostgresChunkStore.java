package io.github.lightragjava.storage.postgres;

import io.github.lightragjava.storage.ChunkStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresChunkStore implements ChunkStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;

    public PostgresChunkStore(DataSource dataSource, PostgresStorageConfig config) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config);
    }

    PostgresChunkStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("chunks");
    }

    @Override
    public void save(ChunkRecord chunk) {
        var record = Objects.requireNonNull(chunk, "chunk");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (id, document_id, text, token_count, chunk_order, metadata)
                VALUES (?, ?, ?, ?, ?, CAST(? AS JSONB))
                ON CONFLICT (id) DO UPDATE
                SET document_id = EXCLUDED.document_id,
                    text = EXCLUDED.text,
                    token_count = EXCLUDED.token_count,
                    chunk_order = EXCLUDED.chunk_order,
                    metadata = EXCLUDED.metadata
                """.formatted(tableName)
            )) {
                statement.setString(1, record.id());
                statement.setString(2, record.documentId());
                statement.setString(3, record.text());
                statement.setInt(4, record.tokenCount());
                statement.setInt(5, record.order());
                statement.setString(6, JdbcJsonCodec.writeStringMap(record.metadata()));
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
                WHERE id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
    public List<ChunkRecord> list() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT id, document_id, text, token_count, chunk_order, metadata
                FROM %s
                ORDER BY id
                """.formatted(tableName)
            ); var resultSet = statement.executeQuery()) {
                var chunks = new java.util.ArrayList<ChunkRecord>();
                while (resultSet.next()) {
                    chunks.add(readChunk(resultSet));
                }
                return List.copyOf(chunks);
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
                WHERE document_id = ?
                ORDER BY chunk_order, id
                """.formatted(tableName)
            )) {
                statement.setString(1, id);
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
}
