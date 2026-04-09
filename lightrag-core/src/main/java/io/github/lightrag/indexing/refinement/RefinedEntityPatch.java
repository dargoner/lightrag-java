package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.ExtractedEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record RefinedEntityPatch(
    ExtractedEntity entity,
    List<String> supportingChunkIds
) {
    public RefinedEntityPatch {
        entity = Objects.requireNonNull(entity, "entity");
        supportingChunkIds = normalizeChunkIds(supportingChunkIds);
    }

    private static List<String> normalizeChunkIds(List<String> supportingChunkIds) {
        var normalized = new LinkedHashSet<String>();
        for (var chunkId : Objects.requireNonNull(supportingChunkIds, "supportingChunkIds")) {
            if (chunkId != null && !chunkId.isBlank()) {
                normalized.add(chunkId.strip());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("supportingChunkIds must not be empty");
        }
        return List.copyOf(normalized);
    }
}
