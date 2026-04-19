package io.github.lightrag.api;

import java.util.Objects;

public record TaskDocumentSnapshot(
    String taskId,
    String documentId,
    DocumentStatus status,
    int chunkCount,
    int entityCount,
    int relationCount,
    int chunkVectorCount,
    int entityVectorCount,
    int relationVectorCount,
    String errorMessage
) {
    public TaskDocumentSnapshot {
        taskId = requireNonBlank(taskId, "taskId");
        documentId = requireNonBlank(documentId, "documentId");
        status = Objects.requireNonNull(status, "status");
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
