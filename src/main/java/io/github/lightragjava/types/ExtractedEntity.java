package io.github.lightragjava.types;

import java.util.List;
import java.util.Objects;

public record ExtractedEntity(String name, String type, String description, List<String> aliases) {
    public ExtractedEntity {
        name = requireNonBlank(name, "name");
        type = type == null ? "" : type.strip();
        description = description == null ? "" : description.strip();
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
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
