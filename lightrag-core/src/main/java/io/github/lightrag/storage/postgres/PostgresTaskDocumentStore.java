package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.TaskDocumentStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresTaskDocumentStore implements TaskDocumentStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresTaskDocumentStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresTaskDocumentStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresTaskDocumentStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("task_document");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(TaskDocumentRecord record) {
        var taskDocument = Objects.requireNonNull(record, "record");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, task_id, document_id, status, chunk_count, entity_count, relation_count,
                    chunk_vector_count, entity_vector_count, relation_vector_count, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, task_id, document_id) DO UPDATE
                SET status = EXCLUDED.status,
                    chunk_count = EXCLUDED.chunk_count,
                    entity_count = EXCLUDED.entity_count,
                    relation_count = EXCLUDED.relation_count,
                    chunk_vector_count = EXCLUDED.chunk_vector_count,
                    entity_vector_count = EXCLUDED.entity_vector_count,
                    relation_vector_count = EXCLUDED.relation_vector_count,
                    error_message = EXCLUDED.error_message
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, taskDocument.taskId());
                statement.setString(3, taskDocument.documentId());
                statement.setString(4, taskDocument.status().name());
                statement.setInt(5, taskDocument.chunkCount());
                statement.setInt(6, taskDocument.entityCount());
                statement.setInt(7, taskDocument.relationCount());
                statement.setInt(8, taskDocument.chunkVectorCount());
                statement.setInt(9, taskDocument.entityVectorCount());
                statement.setInt(10, taskDocument.relationVectorCount());
                statement.setString(11, taskDocument.errorMessage());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<TaskDocumentRecord> load(String taskId, String documentId) {
        var normalizedTaskId = Objects.requireNonNull(taskId, "taskId");
        var normalizedDocumentId = Objects.requireNonNull(documentId, "documentId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT task_id, document_id, status, chunk_count, entity_count, relation_count,
                       chunk_vector_count, entity_vector_count, relation_vector_count, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                  AND document_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedTaskId);
                statement.setString(3, normalizedDocumentId);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readRecord(resultSet));
                }
            }
        });
    }

    @Override
    public List<TaskDocumentRecord> listByTask(String taskId) {
        var normalizedTaskId = Objects.requireNonNull(taskId, "taskId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT task_id, document_id, status, chunk_count, entity_count, relation_count,
                       chunk_vector_count, entity_vector_count, relation_vector_count, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                ORDER BY document_id ASC
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedTaskId);
                try (var resultSet = statement.executeQuery()) {
                    var records = new java.util.ArrayList<TaskDocumentRecord>();
                    while (resultSet.next()) {
                        records.add(readRecord(resultSet));
                    }
                    return List.copyOf(records);
                }
            }
        });
    }

    @Override
    public void deleteByTask(String taskId) {
        var normalizedTaskId = Objects.requireNonNull(taskId, "taskId");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, normalizedTaskId);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private static TaskDocumentRecord readRecord(ResultSet resultSet) throws SQLException {
        return new TaskDocumentRecord(
            resultSet.getString("task_id"),
            resultSet.getString("document_id"),
            DocumentStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("chunk_count"),
            resultSet.getInt("entity_count"),
            resultSet.getInt("relation_count"),
            resultSet.getInt("chunk_vector_count"),
            resultSet.getInt("entity_vector_count"),
            resultSet.getInt("relation_vector_count"),
            resultSet.getString("error_message")
        );
    }
}
