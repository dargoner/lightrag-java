package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryRelation(
    String id,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    List<String> sourceChunkIds,
    String filePath,
    double score
) {
    public StructuredQueryRelation {
        id = requireNonBlank(id, "id");
        srcId = requireNonBlank(srcId, "srcId");
        tgtId = requireNonBlank(tgtId, "tgtId");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        filePath = filePath == null ? "" : filePath.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
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
