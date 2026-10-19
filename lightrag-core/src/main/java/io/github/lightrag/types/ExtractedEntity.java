package io.github.lightrag.types;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record ExtractedEntity(
    String name,
    String type,
    String description,
    List<String> aliases,
    Map<String, Object> attributes
) {
    public ExtractedEntity(String name, String type, String description, List<String> aliases) {
        this(name, type, description, aliases, Map.of());
    }

    public ExtractedEntity {
        name = requireNonBlank(name, "name");
        type = type == null ? "" : type.strip();
        description = description == null ? "" : description.strip();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
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
