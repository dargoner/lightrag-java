package io.github.lightrag.api;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.List;
import java.util.Objects;

public record GraphRelation(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) {
    public GraphRelation(
        String relationId,
        String srcId,
        String tgtId,
        String keywords,
        String description,
        double weight,
        List<String> sourceChunkIds
    ) {
        this(
            relationId,
            srcId,
            tgtId,
            keywords,
            description,
            weight,
            RelationCanonicalizer.joinValues(sourceChunkIds),
            ""
        );
    }

    public GraphRelation {
        relationId = requireNonBlank(relationId, "relationId");
        srcId = CreateRelationRequest.normalizeEndpoint(srcId, "srcId");
        tgtId = CreateRelationRequest.normalizeEndpoint(tgtId, "tgtId");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        sourceId = sourceId == null ? "" : sourceId.strip();
        filePath = filePath == null ? "" : filePath.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
    }

    public String id() {
        return relationId;
    }

    public List<String> sourceChunkIds() {
        return RelationCanonicalizer.splitValues(sourceId);
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
