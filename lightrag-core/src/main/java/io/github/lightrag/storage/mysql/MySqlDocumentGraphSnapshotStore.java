package io.github.lightrag.storage.mysql;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MySqlDocumentGraphSnapshotStore implements DocumentGraphSnapshotStore {
    private final MySqlJdbcConnectionAccess connectionAccess;
    private final String documentTableName;
    private final String chunkTableName;
    private final String workspaceId;

    public MySqlDocumentGraphSnapshotStore(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, "default");
    }

    public MySqlDocumentGraphSnapshotStore(DataSource dataSource, MySqlStorageConfig config, String workspaceId) {
        this(MySqlJdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    MySqlDocumentGraphSnapshotStore(
        MySqlJdbcConnectionAccess connectionAccess,
        MySqlStorageConfig config,
        String workspaceId
    ) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.documentTableName = Objects.requireNonNull(config, "config").qualifiedTableName("document_graph_snapshots");
        this.chunkTableName = config.qualifiedTableName("chunk_graph_snapshots");
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
                ON DUPLICATE KEY UPDATE
                    version = VALUES(version),
                    status = VALUES(status),
                    source = VALUES(source),
                    chunk_count = VALUES(chunk_count),
                    created_at = VALUES(created_at),
                    updated_at = VALUES(updated_at),
                    error_message = VALUES(error_message)
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                bindDocumentSnapshot(statement, value);
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
        var copy = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        for (var chunk : copy) {
            Objects.requireNonNull(chunk, "chunk");
            if (!chunk.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("chunk documentId must match documentId");
            }
        }

        connectionAccess.withConnection(connection -> {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertChunks(connection, copy);
                deleteRemovedChunks(connection, normalizedDocumentId, copy);
                connection.commit();
                return null;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        });
    }

    @Override
    public List<ChunkGraphSnapshot> listChunks(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, chunk_id, chunk_order, content_hash, extract_status,
                       entities, relations, updated_at, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                ORDER BY chunk_order, chunk_id
                """.formatted(chunkTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    var chunks = new ArrayList<ChunkGraphSnapshot>();
                    while (resultSet.next()) {
                        chunks.add(readChunkSnapshot(resultSet));
                    }
                    return List.copyOf(chunks);
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
            try (var deleteChunks = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(chunkTableName)
            ); var deleteDocument = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                """.formatted(documentTableName)
            )) {
                deleteChunks.setString(1, workspaceId);
                deleteChunks.setString(2, normalizedDocumentId);
                deleteChunks.executeUpdate();
                deleteDocument.setString(1, workspaceId);
                deleteDocument.setString(2, normalizedDocumentId);
                deleteDocument.executeUpdate();
                connection.commit();
                return null;
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        });
    }

    private void upsertChunks(java.sql.Connection connection, List<ChunkGraphSnapshot> chunks) throws SQLException {
        if (chunks.isEmpty()) {
            return;
        }
        try (var statement = connection.prepareStatement(
            """
            INSERT INTO %s (
                workspace_id, document_id, chunk_id, chunk_order, content_hash, extract_status,
                entities, relations, updated_at, error_message
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                chunk_order = VALUES(chunk_order),
                content_hash = VALUES(content_hash),
                extract_status = VALUES(extract_status),
                entities = VALUES(entities),
                relations = VALUES(relations),
                updated_at = VALUES(updated_at),
                error_message = VALUES(error_message)
            """.formatted(chunkTableName)
        )) {
            for (var chunk : chunks) {
                statement.setString(1, workspaceId);
                bindChunkSnapshot(statement, chunk);
                statement.executeUpdate();
            }
        }
    }

    private void deleteRemovedChunks(
        java.sql.Connection connection,
        String documentId,
        List<ChunkGraphSnapshot> chunks
    ) throws SQLException {
        if (chunks.isEmpty()) {
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
                return;
            }
        }

        var chunkIds = chunks.stream().map(ChunkGraphSnapshot::chunkId).toList();
        var placeholders = String.join(", ", java.util.Collections.nCopies(chunkIds.size(), "?"));
        var sql = """
            DELETE FROM %s
            WHERE workspace_id = ?
              AND document_id = ?
              AND chunk_id NOT IN (%s)
            """.formatted(chunkTableName, placeholders);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, workspaceId);
            statement.setString(2, documentId);
            for (int i = 0; i < chunkIds.size(); i++) {
                statement.setString(i + 3, chunkIds.get(i));
            }
            statement.executeUpdate();
        }
    }

    private static void bindDocumentSnapshot(java.sql.PreparedStatement statement, DocumentGraphSnapshot snapshot)
        throws SQLException {
        statement.setString(2, snapshot.documentId());
        statement.setInt(3, snapshot.version());
        statement.setString(4, snapshot.status().name());
        statement.setString(5, snapshot.source().name());
        statement.setInt(6, snapshot.chunkCount());
        statement.setTimestamp(7, java.sql.Timestamp.from(snapshot.createdAt()));
        statement.setTimestamp(8, java.sql.Timestamp.from(snapshot.updatedAt()));
        statement.setString(9, snapshot.errorMessage());
    }

    private static void bindChunkSnapshot(java.sql.PreparedStatement statement, ChunkGraphSnapshot chunk)
        throws SQLException {
        statement.setString(2, chunk.documentId());
        statement.setString(3, chunk.chunkId());
        statement.setInt(4, chunk.chunkOrder());
        statement.setString(5, chunk.contentHash());
        statement.setString(6, chunk.extractStatus().name());
        statement.setString(7, MySqlJsonCodec.writeExtractedEntityList(chunk.entities()));
        statement.setString(8, MySqlJsonCodec.writeExtractedRelationList(chunk.relations()));
        statement.setTimestamp(9, java.sql.Timestamp.from(chunk.updatedAt()));
        statement.setString(10, chunk.errorMessage());
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
            MySqlJsonCodec.readExtractedEntityList(resultSet.getString("entities")),
            MySqlJsonCodec.readExtractedRelationList(resultSet.getString("relations")),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        var timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String normalizeDocumentId(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }

    private static void rollback(java.sql.Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(java.sql.Connection connection, boolean originalAutoCommit) throws SQLException {
        if (connection.getAutoCommit() != originalAutoCommit) {
            connection.setAutoCommit(originalAutoCommit);
        }
    }
}
