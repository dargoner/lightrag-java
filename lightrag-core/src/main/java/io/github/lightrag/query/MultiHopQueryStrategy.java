package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.reasoning.ReasoningPath;

import java.util.List;
import java.util.Objects;

public final class MultiHopQueryStrategy implements QueryStrategy {
    private static final double MIN_ACCEPTED_PATH_SCORE = 0.60d;

    private final SeedContextRetriever seedContextRetriever;
    private final PathRetriever pathRetriever;
    private final PathScorer pathScorer;
    private final ReasoningContextAssembler reasoningContextAssembler;

    public MultiHopQueryStrategy(
        SeedContextRetriever seedContextRetriever,
        PathRetriever pathRetriever,
        PathScorer pathScorer,
        ReasoningContextAssembler reasoningContextAssembler
    ) {
        this.seedContextRetriever = Objects.requireNonNull(seedContextRetriever, "seedContextRetriever");
        this.pathRetriever = Objects.requireNonNull(pathRetriever, "pathRetriever");
        this.pathScorer = Objects.requireNonNull(pathScorer, "pathScorer");
        this.reasoningContextAssembler = Objects.requireNonNull(reasoningContextAssembler, "reasoningContextAssembler");
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var seedContext = seedContextRetriever.retrieve(query);
        var pathResult = pathRetriever.retrieve(query, seedContext);
        var rerankedPaths = pathScorer.rerank(query, pathResult).stream()
            .limit(query.pathTopK())
            .toList();
        if (shouldFallbackToSeedContext(rerankedPaths)) {
            return withFilteredChunks(query, seedContext, seedContext.assembledContext());
        }
        var supportingChunks = reasoningContextAssembler.supportingChunks(rerankedPaths, seedContext.matchedChunks());
        var filteredChunks = QueryMetadataFilterSupport.filterChunks(query, supportingChunks);
        return new QueryContext(
            seedContext.matchedEntities(),
            seedContext.matchedRelations(),
            filteredChunks,
            sameChunkIds(supportingChunks, filteredChunks) ? reasoningContextAssembler.assemble(query, rerankedPaths) : ""
        );
    }

    private static QueryContext withFilteredChunks(QueryRequest request, QueryContext context, String assembledContext) {
        var filteredChunks = QueryMetadataFilterSupport.filterChunks(request, context.matchedChunks());
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            filteredChunks,
            sameChunkIds(context.matchedChunks(), filteredChunks) ? assembledContext : ""
        );
    }

    private static boolean sameChunkIds(List<ScoredChunk> left, List<ScoredChunk> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).chunkId().equals(right.get(i).chunkId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldFallbackToSeedContext(List<ReasoningPath> rerankedPaths) {
        if (rerankedPaths.isEmpty()) {
            return true;
        }
        var bestPath = rerankedPaths.get(0);
        return bestPath.score() < MIN_ACCEPTED_PATH_SCORE
            || bestPath.supportingChunkIds().size() < bestPath.hopCount();
    }
}
