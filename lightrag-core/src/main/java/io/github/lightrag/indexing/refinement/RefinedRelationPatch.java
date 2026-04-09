package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.ExtractedRelation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record RefinedRelationPatch(
    ExtractedRelation relation,
    List<String> supportingChunkIds
) {
    public RefinedRelationPatch {
        relation = Objects.requireNonNull(relation, "relation");
        supportingChunkIds = normalizeChunkIds(supportingChunkIds);
    }

    private static List<String> normalizeChunkIds(List<String> supportingChunkIds) {
        var normalized = new LinkedHashSet<String>();
        for (var chunkId : Objects.requireNonNull(supportingChunkIds, "supportingChunkIds")) {
            if (chunkId != null && !chunkId.isBlank()) {
                normalized.add(chunkId.strip());
            }
        }
        return List.copyOf(normalized);
    }
}
