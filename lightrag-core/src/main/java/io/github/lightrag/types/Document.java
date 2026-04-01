package io.github.lightrag.types;

import java.util.Map;
import java.util.Objects;

public record Document(String id, String title, String content, Map<String, String> metadata) {
    public Document {
        id = requireNonBlank(id, "id");
        title = Objects.requireNonNull(title, "title");
        content = Objects.requireNonNull(content, "content");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
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
