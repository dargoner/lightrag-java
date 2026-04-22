package io.github.lightrag.types.reasoning;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ReasoningPath(
    List<String> entityIds,
    List<RelationEndpoint> relationEndpoints,
    List<String> supportingChunkIds,
    int hopCount,
    double score
) {
    public ReasoningPath {
        entityIds = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        relationEndpoints = List.copyOf(Objects.requireNonNull(relationEndpoints, "relationEndpoints"));
        supportingChunkIds = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(supportingChunkIds, "supportingChunkIds")));
        if (entityIds.size() < 2) {
            throw new IllegalArgumentException("entityIds must contain at least two entities");
        }
        if (relationEndpoints.isEmpty()) {
            throw new IllegalArgumentException("relationEndpoints must not be empty");
        }
        if (entityIds.size() != relationEndpoints.size() + 1) {
            throw new IllegalArgumentException("entityIds must be one longer than relationEndpoints");
        }
        if (hopCount <= 0) {
            throw new IllegalArgumentException("hopCount must be positive");
        }
        if (hopCount != relationEndpoints.size()) {
            throw new IllegalArgumentException("hopCount must equal relationEndpoints.size()");
        }
        if (!Double.isFinite(score) || score < 0.0d) {
            throw new IllegalArgumentException("score must be finite and non-negative");
        }
    }
}
