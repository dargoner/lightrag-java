package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.storage.TaskStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeTaskStore extends ArcadeStoreSupport implements TaskStore {
    public ArcadeTaskStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(TaskRecord taskRecord) {
        var record = Objects.requireNonNull(taskRecord, "taskRecord");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("taskType", record.taskType().name());
        properties.put("status", record.status().name());
        properties.put("requestedAt", record.requestedAt().toString());
        properties.put("startedAt", record.startedAt() == null ? null : record.startedAt().toString());
        properties.put("finishedAt", record.finishedAt() == null ? null : record.finishedAt().toString());
        properties.put("summary", record.summary());
        properties.put("errorMessage", record.errorMessage());
        properties.put("cancelRequested", record.cancelRequested());
        properties.put("metadata", ArcadeJsonCodec.writeStringMap(record.metadata()));
        upsertByWorkspaceId("Task", "taskId", record.taskId(), properties);
    }

    @Override
    public Optional<TaskRecord> load(String taskId) {
        return first(selectBase() + " WHERE workspaceId = ? AND taskId = ? LIMIT 1", workspaceId, taskId).map(this::readTask);
    }

    @Override
    public List<TaskRecord> list() {
        return query(selectBase() + " WHERE workspaceId = ? ORDER BY requestedAt DESC, taskId", workspaceId)
            .stream()
            .map(this::readTask)
            .toList();
    }

    @Override
    public void delete(String taskId) {
        execute("DELETE FROM Task WHERE workspaceId = ? AND taskId = ?", workspaceId, taskId);
    }

    void deleteAll() {
        deleteWorkspaceRows("Task");
    }

    private String selectBase() {
        return "SELECT taskId, workspaceId, taskType, status, requestedAt, startedAt, finishedAt, summary, errorMessage, cancelRequested, metadata FROM Task";
    }

    private TaskRecord readTask(Map<String, Object> row) {
        return new TaskRecord(
            ArcadeRecordMapper.string(row, "taskId"),
            ArcadeRecordMapper.string(row, "workspaceId"),
            TaskType.valueOf(ArcadeRecordMapper.string(row, "taskType")),
            TaskStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            Instant.parse(ArcadeRecordMapper.string(row, "requestedAt")),
            parseInstant(ArcadeRecordMapper.nullableString(row, "startedAt")),
            parseInstant(ArcadeRecordMapper.nullableString(row, "finishedAt")),
            ArcadeRecordMapper.string(row, "summary"),
            ArcadeRecordMapper.nullableString(row, "errorMessage"),
            ArcadeRecordMapper.bool(row, "cancelRequested"),
            ArcadeRecordMapper.stringMap(row, "metadata")
        );
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
