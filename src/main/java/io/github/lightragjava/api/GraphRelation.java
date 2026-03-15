package io.github.lightragjava.api;

import java.util.List;
import java.util.Objects;

public record GraphRelation(
    String id,
    String sourceEntityId,
    String targetEntityId,
    String type,
    String description,
    double weight,
    List<String> sourceChunkIds
) {
    public GraphRelation {
        id = requireNonBlank(id, "id");
        sourceEntityId = requireNonBlank(sourceEntityId, "sourceEntityId");
        targetEntityId = requireNonBlank(targetEntityId, "targetEntityId");
        type = requireNonBlank(type, "type");
        description = description == null ? "" : description.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
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
}
