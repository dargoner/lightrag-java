package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;

import java.util.List;
import java.util.Objects;

public record ChunkExtractionPatch(
    String chunkId,
    List<ExtractedEntity> entities,
    List<ExtractedRelation> relations
) {
    public ChunkExtractionPatch {
        chunkId = Objects.requireNonNull(chunkId, "chunkId").strip();
        if (chunkId.isEmpty()) {
            throw new IllegalArgumentException("chunkId must not be blank");
        }
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
    }
}
