package io.github.lightrag.storage;

import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface TaskStageStore {
    void save(TaskStageRecord taskStageRecord);

    List<TaskStageRecord> listByTask(String taskId);

    void deleteByTask(String taskId);

    record TaskStageRecord(
        String taskId,
        TaskStage stage,
        TaskStageStatus status,
        int sequence,
        Instant startedAt,
        Instant finishedAt,
        String message,
        String errorMessage
    ) {
        public TaskStageRecord {
            taskId = requireNonBlank(taskId, "taskId");
            stage = Objects.requireNonNull(stage, "stage");
            status = Objects.requireNonNull(status, "status");
            message = message == null ? "" : message.strip();
            errorMessage = errorMessage == null ? null : errorMessage.strip();
        }

        private static String requireNonBlank(String value, String fieldName) {
            Objects.requireNonNull(value, fieldName);
            var normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
