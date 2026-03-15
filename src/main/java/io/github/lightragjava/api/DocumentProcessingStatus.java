package io.github.lightragjava.api;

import java.util.Objects;

public record DocumentProcessingStatus(
    String documentId,
    DocumentStatus status,
    String summary,
    String errorMessage
) {
    public DocumentProcessingStatus {
        documentId = requireNonBlank(documentId, "documentId");
        status = Objects.requireNonNull(status, "status");
        summary = summary == null ? "" : summary.strip();
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
