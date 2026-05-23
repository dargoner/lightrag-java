package io.github.lightrag.api;

import java.util.Objects;

public record DeletionResult(
    String status,
    String docId,
    String message,
    int statusCode,
    String filePath
) {
    public DeletionResult {
        status = requireNonBlank(status, "status");
        docId = Objects.requireNonNull(docId, "docId");
        message = Objects.requireNonNull(message, "message");
    }

    public static DeletionResult success(String docId, String message) {
        return new DeletionResult("success", docId, message, 200, null);
    }

    public static DeletionResult notFound(String docId, String message) {
        return new DeletionResult("not_found", docId, message, 404, null);
    }

    public static DeletionResult fail(String docId, String message) {
        return new DeletionResult("fail", docId, message, 500, null);
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
