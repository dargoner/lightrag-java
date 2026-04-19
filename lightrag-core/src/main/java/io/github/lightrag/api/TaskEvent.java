package io.github.lightrag.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record TaskEvent(
    String eventId,
    TaskEventType eventType,
    TaskEventScope scope,
    Instant occurredAt,
    String workspaceId,
    String taskId,
    TaskType taskType,
    String documentId,
    String chunkId,
    TaskStage stage,
    String status,
    String message,
    Map<String, String> attributes
) {
    public TaskEvent {
        eventId = requireNonBlank(eventId, "eventId");
        eventType = Objects.requireNonNull(eventType, "eventType");
        scope = Objects.requireNonNull(scope, "scope");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        workspaceId = requireNonBlank(workspaceId, "workspaceId");
        taskId = requireNonBlank(taskId, "taskId");
        taskType = Objects.requireNonNull(taskType, "taskType");
        status = status == null ? "" : status.strip();
        message = message == null ? "" : message.strip();
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
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
