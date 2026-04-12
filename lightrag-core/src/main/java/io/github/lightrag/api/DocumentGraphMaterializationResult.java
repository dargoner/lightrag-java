package io.github.lightrag.api;

import java.util.Objects;

public record DocumentGraphMaterializationResult(
    String documentId,
    GraphMaterializationMode mode,
    GraphMaterializationStatus status,
    SnapshotStatus snapshotStatus,
    int snapshotVersion,
    int materializedEntityCount,
    int materializedRelationCount,
    FailureStage failureStage,
    String summary,
    String errorMessage
) {
    public DocumentGraphMaterializationResult {
        documentId = requireNonBlank(documentId, "documentId");
        mode = Objects.requireNonNull(mode, "mode");
        status = Objects.requireNonNull(status, "status");
        snapshotStatus = Objects.requireNonNull(snapshotStatus, "snapshotStatus");
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
