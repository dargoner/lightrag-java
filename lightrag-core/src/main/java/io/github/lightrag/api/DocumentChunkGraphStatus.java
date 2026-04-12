package io.github.lightrag.api;

import java.util.Objects;

public record DocumentChunkGraphStatus(
    String documentId,
    String chunkId,
    int chunkOrder,
    ChunkExtractStatus extractStatus,
    ChunkMergeStatus mergeStatus,
    ChunkGraphStatus graphStatus,
    int expectedEntityCount,
    int expectedRelationCount,
    int materializedEntityCount,
    int materializedRelationCount,
    java.util.List<String> missingEntityKeys,
    java.util.List<String> missingRelationKeys,
    boolean repairable,
    GraphChunkAction recommendedAction,
    String errorMessage
) {
    public DocumentChunkGraphStatus {
        documentId = requireNonBlank(documentId, "documentId");
        chunkId = requireNonBlank(chunkId, "chunkId");
        extractStatus = Objects.requireNonNull(extractStatus, "extractStatus");
        mergeStatus = Objects.requireNonNull(mergeStatus, "mergeStatus");
        graphStatus = Objects.requireNonNull(graphStatus, "graphStatus");
        missingEntityKeys = java.util.List.copyOf(Objects.requireNonNull(missingEntityKeys, "missingEntityKeys"));
        missingRelationKeys = java.util.List.copyOf(Objects.requireNonNull(missingRelationKeys, "missingRelationKeys"));
        recommendedAction = Objects.requireNonNull(recommendedAction, "recommendedAction");
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
