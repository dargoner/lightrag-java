package io.github.lightragjava.types;

import java.util.List;
import java.util.Objects;

public record Entity(
    String id,
    String name,
    String type,
    String description,
    List<String> aliases,
    List<String> sourceChunkIds
) {
    public Entity {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        type = normalizeOptional(type, "type");
        description = normalizeOptional(description, "description");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
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
