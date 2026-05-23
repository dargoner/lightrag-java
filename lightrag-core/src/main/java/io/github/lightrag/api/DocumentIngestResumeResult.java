package io.github.lightrag.api;

import java.util.Objects;

public record DocumentIngestResumeResult(
    String documentId,
    DocumentIngestResumeAction executedAction,
    DocumentStatus finalDocumentStatus,
    GraphMaterializationStatus finalGraphStatus,
    String summary,
    String errorMessage
) {
    public DocumentIngestResumeResult {
        documentId = requireNonBlank(documentId, "documentId");
        executedAction = Objects.requireNonNull(executedAction, "executedAction");
        finalDocumentStatus = Objects.requireNonNull(finalDocumentStatus, "finalDocumentStatus");
        finalGraphStatus = Objects.requireNonNull(finalGraphStatus, "finalGraphStatus");
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
