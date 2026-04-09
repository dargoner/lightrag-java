package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;

import java.util.List;
import java.util.Objects;

public record RefinementWindow(
    String documentId,
    List<Chunk> chunks,
    int anchorChunkIndex,
    RefinementScope scope,
    int estimatedTokenCount
) {
    public RefinementWindow {
        documentId = requireNonBlank(documentId, "documentId");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
        for (var chunk : chunks) {
            Objects.requireNonNull(chunk, "chunks entry");
        }
        if (anchorChunkIndex < 0 || anchorChunkIndex >= chunks.size()) {
            throw new IllegalArgumentException("anchorChunkIndex is out of range");
        }
        scope = Objects.requireNonNull(scope, "scope");
        if (estimatedTokenCount < 0) {
            throw new IllegalArgumentException("estimatedTokenCount must not be negative");
        }
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
