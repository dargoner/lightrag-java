package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.storage.DocumentGraphJournalStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PostgresDocumentGraphJournalStore implements DocumentGraphJournalStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String documentTableName;
    private final String chunkTableName;
    private final String workspaceId;

    public PostgresDocumentGraphJournalStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresDocumentGraphJournalStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresDocumentGraphJournalStore(
        JdbcConnectionAccess connectionAccess,
        PostgresStorageConfig config,
        String workspaceId
    ) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        var storageConfig = Objects.requireNonNull(config, "config");
        this.documentTableName = storageConfig.qualifiedTableName("document_graph_journals");
        this.chunkTableName = storageConfig.qualifiedTableName("chunk_graph_journals");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        var entry = Objects.requireNonNull(journal, "journal");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, document_id, snapshot_version, status, last_mode,
                    expected_entity_count, expected_relation_count, materialized_entity_count, materialized_relation_count,
                    last_failure_stage, created_at, updated_at, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, document_id) DO UPDATE
                SET snapshot_version = EXCLUDED.snapshot_version,
                    status = EXCLUDED.status,
                    last_mode = EXCLUDED.last_mode,
                    expected_entity_count = EXCLUDED.expected_entity_count,
                    expected_relation_count = EXCLUDED.expected_relation_count,
                    materialized_entity_count = EXCLUDED.materialized_entity_count,
                    materialized_relation_count = EXCLUDED.materialized_relation_count,
                    last_failure_stage = EXCLUDED.last_failure_stage,
                    created_at = EXCLUDED.created_at,
                    updated_at = EXCLUDED.updated_at,
                    error_message = EXCLUDED.error_message
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, entry.documentId());
                statement.setInt(3, entry.snapshotVersion());
                statement.setString(4, entry.status().name());
                statement.setString(5, entry.lastMode().name());
                statement.setInt(6, entry.expectedEntityCount());
                statement.setInt(7, entry.expectedRelationCount());
                statement.setInt(8, entry.materializedEntityCount());
                statement.setInt(9, entry.materializedRelationCount());
                statement.setString(10, enumName(entry.lastFailureStage()));
                statement.setTimestamp(11, Timestamp.from(entry.createdAt()));
                statement.setTimestamp(12, Timestamp.from(entry.updatedAt()));
                statement.setString(13, entry.errorMessage());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<DocumentGraphJournal> listDocumentJournals(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, snapshot_version, status, last_mode,
                       expected_entity_count, expected_relation_count, materialized_entity_count, materialized_relation_count,
                       last_failure_stage, created_at, updated_at, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                ORDER BY document_id
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    var rows = new ArrayList<DocumentGraphJournal>();
                    while (resultSet.next()) {
                        rows.add(readDocumentJournal(resultSet));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var entries = List.copyOf(Objects.requireNonNull(journals, "journals"));
        for (var journal : entries) {
            Objects.requireNonNull(journal, "journal");
            if (!journal.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("journal documentId must match documentId");
            }
        }
        if (entries.isEmpty()) {
            return;
        }

        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, document_id, chunk_id, snapshot_version, merge_status, graph_status,
                    expected_entity_keys, expected_relation_keys, materialized_entity_keys, materialized_relation_keys,
                    last_failure_stage, updated_at, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB), CAST(? AS JSONB), ?, ?, ?)
                ON CONFLICT (workspace_id, document_id, chunk_id) DO UPDATE
                SET snapshot_version = EXCLUDED.snapshot_version,
                    merge_status = EXCLUDED.merge_status,
                    graph_status = EXCLUDED.graph_status,
                    expected_entity_keys = EXCLUDED.expected_entity_keys,
                    expected_relation_keys = EXCLUDED.expected_relation_keys,
                    materialized_entity_keys = EXCLUDED.materialized_entity_keys,
                    materialized_relation_keys = EXCLUDED.materialized_relation_keys,
                    last_failure_stage = EXCLUDED.last_failure_stage,
                    updated_at = EXCLUDED.updated_at,
                    error_message = EXCLUDED.error_message
                """.formatted(chunkTableName)
            )) {
                for (var entry : entries) {
                    statement.setString(1, workspaceId);
                    statement.setString(2, entry.documentId());
                    statement.setString(3, entry.chunkId());
                    statement.setInt(4, entry.snapshotVersion());
                    statement.setString(5, entry.mergeStatus().name());
                    statement.setString(6, entry.graphStatus().name());
                    statement.setString(7, JdbcJsonCodec.writeStringList(entry.expectedEntityKeys()));
                    statement.setString(8, JdbcJsonCodec.writeStringList(entry.expectedRelationKeys()));
                    statement.setString(9, JdbcJsonCodec.writeStringList(entry.materializedEntityKeys()));
                    statement.setString(10, JdbcJsonCodec.writeStringList(entry.materializedRelationKeys()));
                    statement.setString(11, enumName(entry.lastFailureStage()));
                    statement.setTimestamp(12, Timestamp.from(entry.updatedAt()));
                    statement.setString(13, entry.errorMessage());
                    statement.addBatch();
                }
                statement.executeBatch();
                return null;
            }
        });
    }

    @Override
    public List<ChunkGraphJournal> listChunkJournals(String documentId) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT document_id, chunk_id, snapshot_version, merge_status, graph_status,
                       expected_entity_keys, expected_relation_keys, materialized_entity_keys, materialized_relation_keys,
                       last_failure_stage, updated_at, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND document_id = ?
                ORDER BY chunk_id
                """.formatted(chunkTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    var rows = new ArrayList<ChunkGraphJournal>();
                    while (resultSet.next()) {
                        rows.add(readChunkJournal(resultSet));
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
                try (var deleteChunks = connection.prepareStatement(
                    """
                    DELETE FROM %s
                    WHERE workspace_id = ?
                      AND document_id = ?
                    """.formatted(chunkTableName)
                )) {
                    deleteChunks.setString(1, workspaceId);
                    deleteChunks.setString(2, normalizedDocumentId);
                    deleteChunks.executeUpdate();
                }
                try (var deleteDocument = connection.prepareStatement(
                    """
                    DELETE FROM %s
                    WHERE workspace_id = ?
                      AND document_id = ?
                    """.formatted(documentTableName)
                )) {
                    deleteDocument.setString(1, workspaceId);
                    deleteDocument.setString(2, normalizedDocumentId);
                    deleteDocument.executeUpdate();
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

    private static DocumentGraphJournal readDocumentJournal(ResultSet resultSet) throws SQLException {
        return new DocumentGraphJournal(
            resultSet.getString("document_id"),
            resultSet.getInt("snapshot_version"),
            GraphMaterializationStatus.valueOf(resultSet.getString("status")),
            GraphMaterializationMode.valueOf(resultSet.getString("last_mode")),
            resultSet.getInt("expected_entity_count"),
            resultSet.getInt("expected_relation_count"),
            resultSet.getInt("materialized_entity_count"),
            resultSet.getInt("materialized_relation_count"),
            readFailureStage(resultSet, "last_failure_stage"),
            readInstant(resultSet, "created_at"),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static ChunkGraphJournal readChunkJournal(ResultSet resultSet) throws SQLException {
        return new ChunkGraphJournal(
            resultSet.getString("document_id"),
            resultSet.getString("chunk_id"),
            resultSet.getInt("snapshot_version"),
            ChunkMergeStatus.valueOf(resultSet.getString("merge_status")),
            ChunkGraphStatus.valueOf(resultSet.getString("graph_status")),
            JdbcJsonCodec.readStringList(resultSet.getString("expected_entity_keys")),
            JdbcJsonCodec.readStringList(resultSet.getString("expected_relation_keys")),
            JdbcJsonCodec.readStringList(resultSet.getString("materialized_entity_keys")),
            JdbcJsonCodec.readStringList(resultSet.getString("materialized_relation_keys")),
            readFailureStage(resultSet, "last_failure_stage"),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FailureStage readFailureStage(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getString(column);
        return value == null ? null : FailureStage.valueOf(value);
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
