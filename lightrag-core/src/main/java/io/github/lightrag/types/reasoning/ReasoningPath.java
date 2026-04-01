package io.github.lightrag.types.reasoning;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ReasoningPath(
    List<String> entityIds,
    List<String> relationIds,
    List<String> supportingChunkIds,
    int hopCount,
    double score
) {
    public ReasoningPath {
        entityIds = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        relationIds = List.copyOf(Objects.requireNonNull(relationIds, "relationIds"));
        supportingChunkIds = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(supportingChunkIds, "supportingChunkIds")));
        if (entityIds.size() < 2) {
            throw new IllegalArgumentException("entityIds must contain at least two entities");
        }
        if (relationIds.isEmpty()) {
            throw new IllegalArgumentException("relationIds must not be empty");
        }
        if (entityIds.size() != relationIds.size() + 1) {
            throw new IllegalArgumentException("entityIds must be one longer than relationIds");
        }
        if (hopCount <= 0) {
            throw new IllegalArgumentException("hopCount must be positive");
        }
        if (hopCount != relationIds.size()) {
            throw new IllegalArgumentException("hopCount must equal relationIds.size()");
        }
        if (!Double.isFinite(score) || score < 0.0d) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }
}
