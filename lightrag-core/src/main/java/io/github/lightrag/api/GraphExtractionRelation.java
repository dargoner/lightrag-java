package io.github.lightrag.api;

import java.util.Objects;

public record GraphExtractionRelation(
    String node1,
    String node2,
    String type
) {
    public GraphExtractionRelation {
        node1 = requireNonBlank(node1, "relation node1");
        node2 = requireNonBlank(node2, "relation node2");
        type = requireNonBlank(type, "relation type");
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
