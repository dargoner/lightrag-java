package io.github.lightrag.types;

import java.util.Objects;

public record ScoredRelation(String relationId, Relation relation, double score) {
    public ScoredRelation {
        relationId = requireNonBlank(relationId, "relationId");
        relation = Objects.requireNonNull(relation, "relation");
        if (!relation.id().equals(relationId)) {
            throw new IllegalArgumentException("relationId must match relation.id");
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
