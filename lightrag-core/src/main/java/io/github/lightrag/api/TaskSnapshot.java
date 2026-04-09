package io.github.lightrag.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record TaskSnapshot(
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
    Map<String, String> metadata,
    List<TaskStageSnapshot> stages
) {
    public TaskSnapshot {
        taskId = requireNonBlank(taskId, "taskId");
        workspaceId = requireNonBlank(workspaceId, "workspaceId");
        taskType = Objects.requireNonNull(taskType, "taskType");
        status = Objects.requireNonNull(status, "status");
        summary = summary == null ? "" : summary.strip();
        errorMessage = errorMessage == null ? null : errorMessage.strip();
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        stages = List.copyOf(Objects.requireNonNull(stages, "stages"));
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
