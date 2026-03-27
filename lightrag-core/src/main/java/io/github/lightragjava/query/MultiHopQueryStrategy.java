package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.QueryContext;

import java.util.Objects;

public final class MultiHopQueryStrategy implements QueryStrategy {
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
        return new QueryContext(
            seedContext.matchedEntities(),
            seedContext.matchedRelations(),
            reasoningContextAssembler.supportingChunks(rerankedPaths, seedContext.matchedChunks()),
            reasoningContextAssembler.assemble(request, rerankedPaths)
        );
    }
}
