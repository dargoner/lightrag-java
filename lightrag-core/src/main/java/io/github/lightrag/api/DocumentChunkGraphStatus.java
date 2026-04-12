package io.github.lightrag.api;

import java.util.Objects;

public record DocumentChunkGraphStatus(
    String documentId,
    String chunkId,
    ChunkExtractStatus extractStatus,
    ChunkMergeStatus mergeStatus,
    ChunkGraphStatus graphStatus,
    SnapshotSource snapshotSource,
    GraphChunkAction recommendedAction,
    boolean repairable,
    String summary
) {
    public DocumentChunkGraphStatus {
        documentId = requireNonBlank(documentId, "documentId");
        chunkId = requireNonBlank(chunkId, "chunkId");
        extractStatus = Objects.requireNonNull(extractStatus, "extractStatus");
        mergeStatus = Objects.requireNonNull(mergeStatus, "mergeStatus");
        graphStatus = Objects.requireNonNull(graphStatus, "graphStatus");
        snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
        recommendedAction = Objects.requireNonNull(recommendedAction, "recommendedAction");
        summary = summary == null ? "" : summary.strip();
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
