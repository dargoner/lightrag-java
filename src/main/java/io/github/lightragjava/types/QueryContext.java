package io.github.lightragjava.types;

import java.util.List;
import java.util.Objects;

public record QueryContext(
    List<ScoredEntity> matchedEntities,
    List<ScoredRelation> matchedRelations,
    List<ScoredChunk> matchedChunks,
    String assembledContext
) {
    public QueryContext {
        matchedEntities = List.copyOf(Objects.requireNonNull(matchedEntities, "matchedEntities"));
        matchedRelations = List.copyOf(Objects.requireNonNull(matchedRelations, "matchedRelations"));
        matchedChunks = List.copyOf(Objects.requireNonNull(matchedChunks, "matchedChunks"));
        assembledContext = Objects.requireNonNull(assembledContext, "assembledContext");
    }
}
