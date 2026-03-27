package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.reasoning.ReasoningPath;

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
        var seedContext = seedContextRetriever.retrieve(request);
        var pathResult = pathRetriever.retrieve(request, seedContext);
        var rerankedPaths = pathScorer.rerank(request, pathResult).stream()
            .limit(request.pathTopK())
            .toList();
        if (shouldFallbackToSeedContext(rerankedPaths)) {
            return seedContext;
        }
        return new QueryContext(
            seedContext.matchedEntities(),
            seedContext.matchedRelations(),
            reasoningContextAssembler.supportingChunks(rerankedPaths, seedContext.matchedChunks()),
            reasoningContextAssembler.assemble(request, rerankedPaths)
        );
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
