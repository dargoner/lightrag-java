package io.github.lightrag.api;

import java.util.Objects;

public record DocumentGraphMaterializationResult(
    String documentId,
    GraphMaterializationMode requestedMode,
    GraphMaterializationMode executedMode,
    GraphMaterializationStatus finalStatus,
    int snapshotVersion,
    int entitiesExpected,
    int relationsExpected,
    int entitiesMaterialized,
    int relationsMaterialized,
    boolean snapshotReused,
    boolean snapshotRecoveredFromStorage,
    String summary,
    String errorMessage
) {
    public DocumentGraphMaterializationResult {
        documentId = requireNonBlank(documentId, "documentId");
        requestedMode = Objects.requireNonNull(requestedMode, "requestedMode");
        executedMode = Objects.requireNonNull(executedMode, "executedMode");
        finalStatus = Objects.requireNonNull(finalStatus, "finalStatus");
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
