package io.github.lightrag.api;

import java.util.Map;
import java.util.Objects;

public record DocumentProcessingStatus(
    String documentId,
    DocumentStatus status,
    String summary,
    String errorMessage,
    Map<String, Object> metadata
) {
    public DocumentProcessingStatus(String documentId, DocumentStatus status, String summary, String errorMessage) {
        this(documentId, status, summary, errorMessage, Map.of());
    }

    public DocumentProcessingStatus {
        documentId = requireNonBlank(documentId, "documentId");
        status = Objects.requireNonNull(status, "status");
        summary = summary == null ? "" : summary.strip();
        errorMessage = errorMessage == null ? null : errorMessage.strip();
        metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
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
