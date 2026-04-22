package io.github.lightrag.api;

import java.util.Map;
import java.util.Objects;

public record StructuredQueryChunk(
    String id,
    String documentId,
    String text,
    int tokenCount,
    int order,
    Map<String, String> metadata,
    double score
) {
    public StructuredQueryChunk {
        id = requireNonBlank(id, "id");
        documentId = requireNonBlank(documentId, "documentId");
        text = Objects.requireNonNull(text, "text");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be non-negative");
        }
        if (order < 0) {
            throw new IllegalArgumentException("order must be non-negative");
        }
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
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
