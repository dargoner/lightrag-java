package io.github.lightrag.types.reasoning;

import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.List;
import java.util.Objects;

public record PathRetrievalResult(
    List<ScoredEntity> seedEntities,
    List<ScoredRelation> seedRelations,
    List<ReasoningPath> paths
) {
    public PathRetrievalResult {
        seedEntities = List.copyOf(Objects.requireNonNull(seedEntities, "seedEntities"));
        seedRelations = List.copyOf(Objects.requireNonNull(seedRelations, "seedRelations"));
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }
}
