package io.github.lightrag.indexing.refinement;

import java.util.List;

public record WindowEntityCandidate(
    String name,
    String type,
    String description,
    List<String> aliases,
    List<Integer> supportingChunkIndexes
) {
    public WindowEntityCandidate {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        supportingChunkIndexes = supportingChunkIndexes == null ? List.of() : List.copyOf(supportingChunkIndexes);
    }
}
