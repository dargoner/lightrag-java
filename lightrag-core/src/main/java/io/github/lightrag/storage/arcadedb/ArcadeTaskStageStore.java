package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.storage.TaskStageStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ArcadeTaskStageStore extends ArcadeStoreSupport implements TaskStageStore {
    public ArcadeTaskStageStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void save(TaskStageRecord taskStageRecord) {
        var record = Objects.requireNonNull(taskStageRecord, "taskStageRecord");
        var existing = first(
            "SELECT @rid FROM TaskStage WHERE workspaceId = ? AND taskId = ? AND stage = ? LIMIT 1",
            workspaceId,
            record.taskId(),
            record.stage().name()
        );
        var properties = new LinkedHashMap<String, Object>();
        properties.put("status", record.status().name());
        properties.put("sequence", record.sequence());
        properties.put("startedAt", record.startedAt() == null ? null : record.startedAt().toString());
        properties.put("finishedAt", record.finishedAt() == null ? null : record.finishedAt().toString());
        properties.put("message", record.message());
        properties.put("errorMessage", record.errorMessage());
        if (existing.isPresent()) {
            var params = new java.util.ArrayList<>(properties.values());
            params.add(workspaceId);
            params.add(record.taskId());
            params.add(record.stage().name());
            execute(
                "UPDATE TaskStage SET status = ?, sequence = ?, startedAt = ?, finishedAt = ?, message = ?, errorMessage = ? WHERE workspaceId = ? AND taskId = ? AND stage = ?",
                params.toArray()
            );
        } else {
            execute(
                "INSERT INTO TaskStage SET workspaceId = ?, taskId = ?, stage = ?, status = ?, sequence = ?, startedAt = ?, finishedAt = ?, message = ?, errorMessage = ?",
                workspaceId,
                record.taskId(),
                record.stage().name(),
                record.status().name(),
                record.sequence(),
                properties.get("startedAt"),
                properties.get("finishedAt"),
                record.message(),
                record.errorMessage()
            );
        }
    }

    @Override
    public List<TaskStageRecord> listByTask(String taskId) {
        return query(selectBase() + " WHERE workspaceId = ? AND taskId = ? ORDER BY sequence", workspaceId, taskId)
            .stream()
            .map(this::readStage)
            .toList();
    }

    @Override
    public void deleteByTask(String taskId) {
        execute("DELETE FROM TaskStage WHERE workspaceId = ? AND taskId = ?", workspaceId, taskId);
    }

    void deleteAll() {
        deleteWorkspaceRows("TaskStage");
    }

    private String selectBase() {
        return "SELECT taskId, stage, status, sequence, startedAt, finishedAt, message, errorMessage FROM TaskStage";
    }

    private TaskStageRecord readStage(Map<String, Object> row) {
        return new TaskStageRecord(
            ArcadeRecordMapper.string(row, "taskId"),
            TaskStage.valueOf(ArcadeRecordMapper.string(row, "stage")),
            TaskStageStatus.valueOf(ArcadeRecordMapper.string(row, "status")),
            ArcadeRecordMapper.integer(row, "sequence"),
            parseInstant(ArcadeRecordMapper.nullableString(row, "startedAt")),
            parseInstant(ArcadeRecordMapper.nullableString(row, "finishedAt")),
            ArcadeRecordMapper.string(row, "message"),
            ArcadeRecordMapper.nullableString(row, "errorMessage")
        );
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
