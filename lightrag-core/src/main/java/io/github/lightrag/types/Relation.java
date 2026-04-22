package io.github.lightrag.types;

import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.List;
import java.util.Objects;

public record Relation(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath
) {
    public Relation(
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

    public Relation {
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

    public List<String> sourceChunkIds() {
        return RelationCanonicalizer.splitValues(sourceId);
    }

    public List<String> filePaths() {
        return RelationCanonicalizer.splitValues(filePath);
    }

    public String id() {
        return relationId;
    }

    public String sourceEntityId() {
        return srcId;
    }

    public String targetEntityId() {
        return tgtId;
    }

    public String type() {
        return keywords;
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
