package io.github.lightrag.storage.mysql;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.TaskDocumentStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MySqlTaskDocumentStore implements TaskDocumentStore {
    private final MySqlJdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public MySqlTaskDocumentStore(DataSource dataSource, MySqlStorageConfig config) {
        this(dataSource, config, "default");
    }

    public MySqlTaskDocumentStore(DataSource dataSource, MySqlStorageConfig config, String workspaceId) {
        this(MySqlJdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    MySqlTaskDocumentStore(MySqlJdbcConnectionAccess connectionAccess, MySqlStorageConfig config, String workspaceId) {
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
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status),
                    chunk_count = VALUES(chunk_count),
                    entity_count = VALUES(entity_count),
                    relation_count = VALUES(relation_count),
                    chunk_vector_count = VALUES(chunk_vector_count),
                    entity_vector_count = VALUES(entity_vector_count),
                    relation_vector_count = VALUES(relation_vector_count),
                    error_message = VALUES(error_message)
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
