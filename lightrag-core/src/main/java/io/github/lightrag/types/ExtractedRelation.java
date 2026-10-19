package io.github.lightrag.types;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ExtractedRelation(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    Double weight,
    Map<String, Object> attributes
) {
    public ExtractedRelation(
        String sourceEntityName,
        String targetEntityName,
        String keywords,
        String description,
        Double weight
    ) {
        this(sourceEntityName, targetEntityName, keywords, description, weight, Map.of());
    }

    public ExtractedRelation {
        sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        weight = weight == null ? 1.0d : weight;
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

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
