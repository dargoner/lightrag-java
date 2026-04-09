package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.storage.TaskStageStore;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class PostgresTaskStageStore implements TaskStageStore {
    private final JdbcConnectionAccess connectionAccess;
    private final String tableName;
    private final String workspaceId;

    public PostgresTaskStageStore(DataSource dataSource, PostgresStorageConfig config) {
        this(dataSource, config, "default");
    }

    public PostgresTaskStageStore(DataSource dataSource, PostgresStorageConfig config, String workspaceId) {
        this(JdbcConnectionAccess.forDataSource(dataSource), config, workspaceId);
    }

    PostgresTaskStageStore(JdbcConnectionAccess connectionAccess, PostgresStorageConfig config, String workspaceId) {
        this.connectionAccess = Objects.requireNonNull(connectionAccess, "connectionAccess");
        this.tableName = Objects.requireNonNull(config, "config").qualifiedTableName("task_stage");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    @Override
    public void save(TaskStageRecord taskStageRecord) {
        var record = Objects.requireNonNull(taskStageRecord, "taskStageRecord");
        connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                INSERT INTO %s (
                    workspace_id, task_id, stage, status, sequence, started_at, finished_at, message, error_message
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, task_id, stage) DO UPDATE
                SET status = EXCLUDED.status,
                    sequence = EXCLUDED.sequence,
                    started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at,
                    message = EXCLUDED.message,
                    error_message = EXCLUDED.error_message
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, record.taskId());
                statement.setString(3, record.stage().name());
                statement.setString(4, record.status().name());
                statement.setInt(5, record.sequence());
                statement.setTimestamp(6, toTimestamp(record.startedAt()));
                statement.setTimestamp(7, toTimestamp(record.finishedAt()));
                statement.setString(8, record.message());
                statement.setString(9, record.errorMessage());
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public List<TaskStageRecord> listByTask(String taskId) {
        var id = Objects.requireNonNull(taskId, "taskId");
        return connectionAccess.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                """
                SELECT task_id, stage, status, sequence, started_at, finished_at, message, error_message
                FROM %s
                WHERE workspace_id = ?
                  AND task_id = ?
                ORDER BY sequence ASC, stage ASC
                """.formatted(tableName)
            )) {
                statement.setString(1, workspaceId);
                statement.setString(2, id);
                try (var resultSet = statement.executeQuery()) {
                    var stages = new java.util.ArrayList<TaskStageRecord>();
                    while (resultSet.next()) {
                        stages.add(readStage(resultSet));
                    }
                    return List.copyOf(stages);
                }
            }
        });
    }

    @Override
    public void deleteByTask(String taskId) {
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

    private static TaskStageRecord readStage(ResultSet resultSet) throws SQLException {
        return new TaskStageRecord(
            resultSet.getString("task_id"),
            TaskStage.valueOf(resultSet.getString("stage")),
            TaskStageStatus.valueOf(resultSet.getString("status")),
            resultSet.getInt("sequence"),
            readInstant(resultSet, "started_at"),
            readInstant(resultSet, "finished_at"),
            resultSet.getString("message"),
            resultSet.getString("error_message")
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
