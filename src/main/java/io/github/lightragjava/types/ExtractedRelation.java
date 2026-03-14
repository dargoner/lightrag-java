package io.github.lightragjava.types;

import java.util.Objects;

public record ExtractedRelation(
    String sourceEntityName,
    String targetEntityName,
    String type,
    String description,
    double weight
) {
    public ExtractedRelation {
        sourceEntityName = requireNonBlank(sourceEntityName, "sourceEntityName");
        targetEntityName = requireNonBlank(targetEntityName, "targetEntityName");
        type = requireNonBlank(type, "type");
        description = description == null ? "" : description.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
    }

    public ExtractedRelation(
        String sourceEntityName,
        String targetEntityName,
        String type,
        String description,
        Double weight
    ) {
        this(sourceEntityName, targetEntityName, type, description, weight == null ? 1.0d : weight.doubleValue());
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
