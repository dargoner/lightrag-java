package io.github.lightrag.api;

import java.util.Objects;

public record ChunkGraphMaterializationResult(
    String documentId,
    String chunkId,
    GraphChunkAction executedAction,
    ChunkGraphStatus finalStatus,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    String summary,
    String errorMessage
) {
    public ChunkGraphMaterializationResult {
        documentId = requireNonBlank(documentId, "documentId");
        chunkId = requireNonBlank(chunkId, "chunkId");
        executedAction = Objects.requireNonNull(executedAction, "executedAction");
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
