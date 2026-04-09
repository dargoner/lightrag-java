package io.github.lightrag.indexing.refinement;

import java.util.List;

public record WindowRelationCandidate(
    String sourceEntityName,
    String targetEntityName,
    String type,
    String description,
    Double weight,
    List<Integer> supportingChunkIndexes
) {
    public WindowRelationCandidate {
        supportingChunkIndexes = supportingChunkIndexes == null ? List.of() : List.copyOf(supportingChunkIndexes);
    }
}
