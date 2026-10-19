package io.github.lightrag.types;

import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record Relation(
    String relationId,
    String srcId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    String sourceId,
    String filePath,
    Map<String, Object> attributes
) {
    public Relation(
        String relationId,
        String srcId,
        String tgtId,
        String keywords,
        String description,
        double weight,
        String sourceId,
        String filePath
    ) {
        this(relationId, srcId, tgtId, keywords, description, weight, sourceId, filePath, Map.of());
    }

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
            "",
            Map.of()
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
        attributes = normalizeAttributes(attributes);
    }

    private static Map<String, Object> normalizeAttributes(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        var sorted = new TreeMap<String, Object>();
        for (var entry : attributes.entrySet()) {
            var key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException("attribute key must not be null");
            }
            var value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException("attribute value must not be null: " + key);
            }
            sorted.put(key, copyAttributeValue(key, value));
        }
        return Collections.unmodifiableMap(sorted);
    }

    private static Object copyAttributeValue(String key, Object value) {
        if (!(value instanceof List<?> values)) {
            return value;
        }
        for (var element : values) {
            if (element == null) {
                throw new IllegalArgumentException("attribute list value must not contain null: " + key);
            }
        }
        return List.copyOf(values);
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

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
