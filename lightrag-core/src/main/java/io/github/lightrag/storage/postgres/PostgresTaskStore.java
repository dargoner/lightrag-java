package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.storage.TaskStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresTaskStore implements TaskStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresTaskStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresTaskStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresTaskStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("task");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(TaskRecord taskRecord) {
        var record = Objects.requireNonNull(taskRecord, "taskRecord");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, task_id, task_type, status, requested_at, started_at, finished_at,
                    summary, error_message, cancel_requested, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (workspace_id, task_id) DO UPDATE
                SET task_type = EXCLUDED.task_type,
                    status = EXCLUDED.status,
                    requested_at = EXCLUDED.requested_at,
                    started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at,
                    summary = EXCLUDED.summary,
                    error_message = EXCLUDED.error_message,
                    cancel_requested = EXCLUDED.cancel_requested,
                    metadata = EXCLUDED.metadata
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.taskId());
                statement.setString(3, record.taskType().name());
                statement.setString(4, record.status().name());
                statement.setTimestamp(5, toTimestamp(record.requestedAt()));
                statement.setTimestamp(6, toTimestamp(record.startedAt()));
                statement.setTimestamp(7, toTimestamp(record.finishedAt()));
                statement.setString(8, record.summary());
                statement.setString(9, record.errorMessage());
                statement.setBoolean(10, record.cancelRequested());
                statement.setString(11, JdbcJsonCodec.writeStringMap(record.metadata()));
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<TaskRecord> load(String taskId) {
        var id = Objects.requireNonNull(taskId, "taskId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT task_id, task_type, status, requested_at, started_at, finished_at,
                       summary, error_message, cancel_requested, metadata
                FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readTask(resultSet));
                }
            }
        });
    }

    @Override
    public List<TaskRecord> list() {
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT task_id, task_type, status, requested_at, started_at, finished_at,
                       summary, error_message, cancel_requested, metadata
                FROM %s
                WHERE workspace_id = ?
                ORDER BY requested_at DESC, task_id DESC
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                try (var resultSet = statement.executeQuery()) {
                    var tasks = new java.util.ArrayList<TaskRecord>();
                    while (resultSet.next()) {
                        tasks.add(readTask(resultSet));
                    }
                    return List.copyOf(tasks);
                }
            }
        });
    }

    @Override
    public void delete(String taskId) {
        var id = Objects.requireNonNull(taskId, "taskId");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                DELETE FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                statement.executeUpdate();
                return null;
            }
        });
    }

    private TaskRecord readTask(ResultSet resultSet) throws SQLException {
        return new TaskRecord(
            resultSet.getString("task_id"),
            workspaceId,
            TaskType.valueOf(resultSet.getString("task_type")),
            TaskStatus.valueOf(resultSet.getString("status")),
            readInstant(resultSet, "requested_at"),
            readInstant(resultSet, "started_at"),
            readInstant(resultSet, "finished_at"),
            resultSet.getString("summary"),
            resultSet.getString("error_message"),
            resultSet.getBoolean("cancel_requested"),
            JdbcJsonCodec.readStringMap(resultSet.getString("metadata"))
        );
    }

    private static Instant readInstant(ResultSet resultSet, String columnName) throws SQLException {
        var timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
