package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record GraphExtractionNode(
    String name,
    List<String> attributes
) {
    public GraphExtractionNode {
        name = requireNonBlank(name, "node name");
        attributes = attributes == null
            ? List.of()
            : List.copyOf(attributes).stream()
                .map(attribute -> requireNonBlank(attribute, "node attribute"))
                .toList();
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
