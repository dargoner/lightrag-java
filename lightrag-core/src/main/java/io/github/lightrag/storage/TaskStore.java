package io.github.lightrag.storage;

import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface TaskStore {
    void save(TaskRecord taskRecord);

    Optional<TaskRecord> load(String taskId);

    List<TaskRecord> list();

    void delete(String taskId);

    record TaskRecord(
        String taskId,
        String workspaceId,
        TaskType taskType,
        TaskStatus status,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        String summary,
        String errorMessage,
        boolean cancelRequested,
        Map<String, String> metadata
    ) {
        public TaskRecord {
            taskId = requireNonBlank(taskId, "taskId");
            workspaceId = requireNonBlank(workspaceId, "workspaceId");
            taskType = Objects.requireNonNull(taskType, "taskType");
            status = Objects.requireNonNull(status, "status");
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
            summary = summary == null ? "" : summary.strip();
            errorMessage = errorMessage == null ? null : errorMessage.strip();
            metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
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
