package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryEntity(
    String id,
    String name,
    String type,
    String description,
    List<String> aliases,
    List<String> sourceChunkIds,
    double score
) {
    public StructuredQueryEntity {
        id = requireNonBlank(id, "id");
        name = requireNonBlank(name, "name");
        type = type == null ? "" : type.strip();
        description = description == null ? "" : description.strip();
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("score must be finite");
        }
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
