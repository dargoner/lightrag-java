package io.github.lightrag.types;

import java.util.Objects;

public record ScoredEntity(String entityId, Entity entity, double score) {
    public ScoredEntity {
        entityId = requireNonBlank(entityId, "entityId");
        entity = Objects.requireNonNull(entity, "entity");
        if (!entity.id().equals(entityId)) {
            throw new IllegalArgumentException("entityId must match entity.id");
        }
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
