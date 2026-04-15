package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.ScoredChunk;

import java.util.List;
import java.util.Objects;

public final class QueryMetadataFilterSupport {
    private QueryMetadataFilterSupport() {
    }

    public static MetadataFilterPlan buildPlan(QueryRequest request) {
        return MetadataFilterNormalizer.normalize(Objects.requireNonNull(request, "request"));
    }

    public static List<ScoredChunk> filterChunks(QueryRequest request, List<ScoredChunk> chunks) {
        return filterChunks(buildPlan(request), chunks);
    }

    public static List<ScoredChunk> filterChunks(MetadataFilterPlan plan, List<ScoredChunk> chunks) {
        Objects.requireNonNull(plan, "plan");
        var candidates = Objects.requireNonNull(chunks, "chunks");
        if (plan.isEmpty() || candidates.isEmpty()) {
            return candidates;
        }
        return candidates.stream()
            .filter(chunk -> MetadataMatcher.matches(plan, chunk.chunk().metadata()))
            .toList();
    }

    public static List<ScoredChunk> expandAndFilter(
        MetadataFilterPlan plan,
        List<ScoredChunk> chunks,
        ParentChunkExpander parentChunkExpander,
        int limit
    ) {
        Objects.requireNonNull(parentChunkExpander, "parentChunkExpander");
        var prefiltered = filterChunks(plan, chunks);
        var expanded = parentChunkExpander.expand(prefiltered.stream().limit(limit).toList(), limit);
        return filterChunks(plan, expanded);
    }
}
