package io.github.lightrag.storage.mysql;

import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.storage.DocumentGraphJournalStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MySqlDocumentGraphJournalStore implements DocumentGraphJournalStore {
    private final MySqlJdbcConnectionAccess connectionAccess;
    private final String documentTableName;
    private final String chunkTableName;
    private final String workspaceId;

    public MySqlDocumentGraphJournalStore(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, "default");
    }

    public MySqlDocumentGraphJournalStore(DataSource dataSource, MySqlStorageConfig config, String workspaceId) {
        this(MySqlJdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    MySqlDocumentGraphJournalStore(
        MySqlJdbcConnectionAccess connectionAccess,
        MySqlStorageConfig config,
        String workspaceId
    ) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.documentTableName = Objects.requireNonNull(config, "config").qualifiedTableName("document_graph_journals");
        this.chunkTableName = config.qualifiedTableName("chunk_graph_journals");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void appendDocument(DocumentGraphJournal journal) {
        var value = Objects.requireNonNull(journal, "journal");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, document_id, snapshot_version, status, last_mode,
                    expected_entity_count, expected_relation_count, materialized_entity_count, materialized_relation_count,
                    last_failure_stage, created_at, updated_at, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    snapshot_version = VALUES(snapshot_version),
                    status = VALUES(status),
                    last_mode = VALUES(last_mode),
                    expected_entity_count = VALUES(expected_entity_count),
                    expected_relation_count = VALUES(expected_relation_count),
                    materialized_entity_count = VALUES(materialized_entity_count),
                    materialized_relation_count = VALUES(materialized_relation_count),
                    last_failure_stage = VALUES(last_failure_stage),
                    created_at = VALUES(created_at),
                    updated_at = VALUES(updated_at),
                    error_message = VALUES(error_message)
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                bindDocumentJournal(statement, value);
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
                ORDER BY updated_at, document_id
                """.formatted(documentTableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    var journals = new ArrayList<DocumentGraphJournal>();
                    while (resultSet.next()) {
                        journals.add(readDocumentJournal(resultSet));
                    }
                    return List.copyOf(journals);
                }
            }
        });
    }

    @Override
    public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
        var normalizedDocumentId = normalizeDocumentId(documentId);
        var copy = List.copyOf(Objects.requireNonNull(journals, "journals"));
        for (var journal : copy) {
            Objects.requireNonNull(journal, "journal");
            if (!journal.documentId().equals(normalizedDocumentId)) {
                throw new IllegalArgumentException("journal documentId must match documentId");
            }
        }
        if (copy.isEmpty()) {
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    snapshot_version = VALUES(snapshot_version),
                    merge_status = VALUES(merge_status),
                    graph_status = VALUES(graph_status),
                    expected_entity_keys = VALUES(expected_entity_keys),
                    expected_relation_keys = VALUES(expected_relation_keys),
                    materialized_entity_keys = VALUES(materialized_entity_keys),
                    materialized_relation_keys = VALUES(materialized_relation_keys),
                    last_failure_stage = VALUES(last_failure_stage),
                    updated_at = VALUES(updated_at),
                    error_message = VALUES(error_message)
                """.formatted(chunkTableName)
            )) {
                for (var journal : copy) {
                    statement.setString(1, workspaceId);
                    bindChunkJournal(statement, journal);
                    statement.executeUpdate();
                }
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
                    var journals = new ArrayList<ChunkGraphJournal>();
                    while (resultSet.next()) {
                        journals.add(readChunkJournal(resultSet));
                    }
                    return List.copyOf(journals);
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

    private static void bindDocumentJournal(java.sql.PreparedStatement statement, DocumentGraphJournal journal)
        throws SQLException {
        statement.setString(2, journal.documentId());
        statement.setInt(3, journal.snapshotVersion());
        statement.setString(4, journal.status().name());
        statement.setString(5, journal.lastMode().name());
        statement.setInt(6, journal.expectedEntityCount());
        statement.setInt(7, journal.expectedRelationCount());
        statement.setInt(8, journal.materializedEntityCount());
        statement.setInt(9, journal.materializedRelationCount());
        statement.setString(10, toNullableName(journal.lastFailureStage()));
        statement.setTimestamp(11, java.sql.Timestamp.from(journal.createdAt()));
        statement.setTimestamp(12, java.sql.Timestamp.from(journal.updatedAt()));
        statement.setString(13, journal.errorMessage());
    }

    private static void bindChunkJournal(java.sql.PreparedStatement statement, ChunkGraphJournal journal)
        throws SQLException {
        statement.setString(2, journal.documentId());
        statement.setString(3, journal.chunkId());
        statement.setInt(4, journal.snapshotVersion());
        statement.setString(5, journal.mergeStatus().name());
        statement.setString(6, journal.graphStatus().name());
        statement.setString(7, MySqlJsonCodec.writeStringList(journal.expectedEntityKeys()));
        statement.setString(8, MySqlJsonCodec.writeStringList(journal.expectedRelationKeys()));
        statement.setString(9, MySqlJsonCodec.writeStringList(journal.materializedEntityKeys()));
        statement.setString(10, MySqlJsonCodec.writeStringList(journal.materializedRelationKeys()));
        statement.setString(11, toNullableName(journal.lastFailureStage()));
        statement.setTimestamp(12, java.sql.Timestamp.from(journal.updatedAt()));
        statement.setString(13, journal.errorMessage());
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
            toFailureStage(resultSet.getString("last_failure_stage")),
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
            MySqlJsonCodec.readStringList(resultSet.getString("expected_entity_keys")),
            MySqlJsonCodec.readStringList(resultSet.getString("expected_relation_keys")),
            MySqlJsonCodec.readStringList(resultSet.getString("materialized_entity_keys")),
            MySqlJsonCodec.readStringList(resultSet.getString("materialized_relation_keys")),
            toFailureStage(resultSet.getString("last_failure_stage")),
            readInstant(resultSet, "updated_at"),
            resultSet.getString("error_message")
        );
    }

    private static String normalizeDocumentId(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }

    private static Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        var timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String toNullableName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static FailureStage toFailureStage(String value) {
        if (value == null) {
            return null;
        }
        return FailureStage.valueOf(value);
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
