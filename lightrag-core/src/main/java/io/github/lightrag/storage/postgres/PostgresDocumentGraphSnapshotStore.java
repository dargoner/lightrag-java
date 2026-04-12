package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresDocumentGraphSnapshotStore implements DocumentGraphSnapshotStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String documentTableName;
    private final String chunkTableName;
    private final String workspaceId;

    public PostgresDocumentGraphSnapshotStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresDocumentGraphSnapshotStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresDocumentGraphSnapshotStore(
        JdbcConnectionAccess connectionAccess,
        PostgresStorageConfig config,
        String workspaceId
    ) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        var storageConfig = Objects.requireNonNull(config, "config");
        this.documentTableName = storageConfig.qualifiedTableName("document_graph_snapshots");
        this.chunkTableName = storageConfig.qualifiedTableName("chunk_graph_snapshots");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void saveDocument(DocumentGraphSnapshot snapshot) {
        var value = Objects.requireNonNull(snapshot, "snapshot");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, document_id, version, status, source, chunk_count, created_at, updated_at, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, document_id) DO UPDATE
                SET version = EXCLUDED.version,
                    status = EXCLUDED.status,
                    source = EXCLUDED.source,
                    chunk_count = EXCLUDED.chunk_count,
                    created_at = EXCLUDED.created_at,
                    updated_at = EXCLUDED.updated_at,
                    error_message = EXCLUDED.error_message
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, value.documentId());
                statement.setInt(3, value.version());
                statement.setString(4, value.status().name());
                statement.setString(5, value.source().name());
                statement.setInt(6, value.chunkCount());
                statement.setTimestamp(7, Timestamp.from(value.createdAt()));
                statement.setTimestamp(8, Timestamp.from(value.updatedAt()));
                statement.setString(9, value.errorMessage());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<DocumentGraphSnapshot> loadDocument(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, version, status, source, chunk_count, created_at, updated_at, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readDocumentSnapshot(resultSet));
                }
            }
        });
    }

    @Override
    public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var records = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        for (var chunk : records) {
            Objects.requireNonNull(chunk, "chunk");
            if (!chunk.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("chunk documentId must match documentId");
            }
        }

        connectionAccess.withConnection(connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertChunkSnapshots(connection, records);
                deleteChunkSnapshotsNotInSet(connection, normalizedDocumentId, records);
                connection.commit();
                return null;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    @Override
    public List<ChunkGraphSnapshot> listChunks(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, chunk_id, chunk_order, content_hash, extract_status, entities, relations, updated_at, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                ORDER BY chunk_order, chunk_id
                """.formatted(chunkTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    var rows = new ArrayList<ChunkGraphSnapshot>();
                    while (resultSet.next()) {
                        rows.add(readChunkSnapshot(resultSet));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public void delete(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        connectionAccess.withConnection(connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (var chunkDelete = connection.prepareStatement(
                    """
                    DELETE FROM %s
                    WHERE workspace_id = ?
                      AND document_id = ?
                    """.formatted(chunkTableName)
                )) {
                    chunkDelete.setString(1, workspaceId);
                    chunkDelete.setString(2, normalizedDocumentId);
                    chunkDelete.executeUpdate();
                }
                try (var documentDelete = connection.prepareStatement(
                    """
                    DELETE FROM %s
                    WHERE workspace_id = ?
                      AND document_id = ?
                    """.formatted(documentTableName)
                )) {
                    documentDelete.setString(1, workspaceId);
                    documentDelete.setString(2, normalizedDocumentId);
                    documentDelete.executeUpdate();
                }
                connection.commit();
                return null;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        });
    }

    private void upsertChunkSnapshots(java.sql.Connection connection, List<ChunkGraphSnapshot> records) throws SQLException {
        if (records.isEmpty()) {
            return;
        }
        try (var statement = connection.prepareStatement(
            """
            INSERT INTO %s (
                workspace_id, document_id, chunk_id, chunk_order, content_hash, extract_status,
                entities, relations, updated_at, error_message
            )
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?, ?)
            ON CONFLICT (workspace_id, document_id, chunk_id) DO UPDATE
            SET chunk_order = EXCLUDED.chunk_order,
                content_hash = EXCLUDED.content_hash,
                extract_status = EXCLUDED.extract_status,
                entities = EXCLUDED.entities,
                relations = EXCLUDED.relations,
                updated_at = EXCLUDED.updated_at,
                error_message = EXCLUDED.error_message
            """.formatted(chunkTableName)
        )) {
            for (var record : records) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.documentId());
                statement.setString(3, record.chunkId());
                statement.setInt(4, record.chunkOrder());
                statement.setString(5, record.contentHash());
                statement.setString(6, record.extractStatus().name());
                statement.setString(7, JdbcJsonCodec.writeExtractedEntityRecordList(record.entities()));
                statement.setString(8, JdbcJsonCodec.writeExtractedRelationRecordList(record.relations()));
                statement.setTimestamp(9, Timestamp.from(record.updatedAt()));
                statement.setString(10, record.errorMessage());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteChunkSnapshotsNotInSet(
        java.sql.Connection connection,
        String documentId,
        List<ChunkGraphSnapshot> records
    ) throws SQLException {
        if (records.isEmpty()) {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(chunkTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, documentId);
                statement.executeUpdate();
            }
            return;
        }

        var placeholders = "?,".repeat(records.size());
        var sql = """
            DELETE FROM %s
            WHERE workspace_id = ?
              AND document_id = ?
              AND chunk_id NOT IN (%s)
            """.formatted(chunkTableName, placeholders.substring(0, placeholders.length() - 1));
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId);
            statement.setString(2, documentId);
            int index = 3;
            for (var record : records) {
                statement.setString(index++, record.chunkId());
            }
            statement.executeUpdate();
        }
    }

    private static DocumentGraphSnapshot readDocumentSnapshot(ResultSet resultSet) throws SQLException {
        return new DocumentGraphSnapshot(
            resultSet.getString("document_id"),
            resultSet.getInt("version"),
            SnapshotStatus.valueOf(resultSet.getString("status")),
            SnapshotSource.valueOf(resultSet.getString("source")),
            resultSet.getInt("chunk_count"),
            readInstant(resultSet, "created_at"),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static ChunkGraphSnapshot readChunkSnapshot(ResultSet resultSet) throws SQLException {
        return new ChunkGraphSnapshot(
            resultSet.getString("document_id"),
            resultSet.getString("chunk_id"),
            resultSet.getInt("chunk_order"),
            resultSet.getString("content_hash"),
            ChunkExtractStatus.valueOf(resultSet.getString("extract_status")),
            JdbcJsonCodec.readExtractedEntityRecordList(resultSet.getString("entities")),
            JdbcJsonCodec.readExtractedRelationRecordList(resultSet.getString("relations")),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static Instant readInstant(ResultSet resultSet, String column) throws SQLException {
        var timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String normalizeDocumentId(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }

    private static void rollback(java.sql.Connection connection, Exception exception) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }
    }
}
