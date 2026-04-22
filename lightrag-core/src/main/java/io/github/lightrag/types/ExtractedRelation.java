package io.github.lightrag.types;

import java.util.Objects;

public record ExtractedRelation(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    Double weight
) {
    public ExtractedRelation {
        sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        keywords = requireNonBlank(keywords, "keywords");
        description = description == null ? "" : description.strip();
        weight = weight == null ? 1.0d : weight;
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
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
