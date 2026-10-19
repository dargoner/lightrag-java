package io.github.lightrag.types;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record Entity(
    String id,
    String name,
    String type,
    String description,
    List<String> aliases,
    List<String> sourceChunkIds,
    String filePath,
    Map<String, Object> attributes
) {
    public Entity(
        String id,
        String name,
        String type,
        String description,
        List<String> aliases,
        List<String> sourceChunkIds,
        String filePath
    ) {
        this(id, name, type, description, aliases, sourceChunkIds, filePath, Map.of());
    }

    public Entity(
        String id,
        String name,
        String type,
        String description,
        List<String> aliases,
        List<String> sourceChunkIds
    ) {
        this(id, name, type, description, aliases, sourceChunkIds, "", Map.of());
    }

    public Entity {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        type = normalizeOptional(type, "type");
        description = normalizeOptional(description, "description");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        filePath = filePath == null ? "" : filePath.strip();
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

    public List<String> filePaths() {
        return RelationCanonicalizer.splitValues(filePath);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeOptional(String value, String fieldName) {
        Objects.requireNonNull(fieldName, "fieldName");
        return value == null ? "" : value.strip();
    }
}
