package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MixQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(MixQueryStrategy.class);
    private static final String CHUNK_NAMESPACE = "chunks";
    private static final int DIRECT_MATCH_SEARCH_GROWTH_FACTOR = 2;

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final QueryStrategy hybridStrategy;
    private final ContextAssembler contextAssembler;

    public MixQueryStrategy(
        EmbeddingModel embeddingModel,
        StorageProvider storageProvider,
        QueryStrategy hybridStrategy,
        ContextAssembler contextAssembler
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.hybridStrategy = Objects.requireNonNull(hybridStrategy, "hybridStrategy");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var startedAt = System.nanoTime();
        var metadataPlan = QueryMetadataFilterSupport.buildPlan(query);
        var hybridFuture = CompletableFuture.supplyAsync(() -> timedRetrieve(hybridStrategy, query));
        var directChunkFuture = CompletableFuture.supplyAsync(() -> firstDirectChunkPass(query));
        var hybrid = awaitBranch(hybridFuture, directChunkFuture);
        var directChunkPass = awaitBranch(directChunkFuture, hybridFuture);
        var mergeOutcome = mergeDirectChunkMatches(query, metadataPlan, hybrid.context(), directChunkPass);
        var chunkVectorSearchMs = mergeOutcome.chunkVectorSearchMs();
        var matchedChunks = mergeOutcome.matchedChunks();
        var mergeFilterMs = mergeOutcome.mergeFilterMs();
        var context = new QueryContext(
            QueryBudgeting.limitEntities(hybrid.context().matchedEntities(), query.maxEntityTokens()),
            QueryBudgeting.limitRelations(hybrid.context().matchedRelations(), query.maxRelationTokens()),
            matchedChunks,
            ""
        );
        var assembleStartedAt = System.nanoTime();
        var assembledContext = contextAssembler.assemble(context);
        var assembleMs = elapsedMillis(assembleStartedAt);
        log.info(
            "LightRAG mix retrieve completed: mode={}, query={}, topK={}, chunkTopK={}, hybridMs={}, embedMs={}, chunkVectorSearchMs={}, mergeFilterMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            query.topK(),
            query.chunkTopK(),
            hybrid.elapsedMs(),
            directChunkPass.embedMs(),
            chunkVectorSearchMs,
            mergeFilterMs,
            assembleMs,
            elapsedMillis(startedAt),
            context.matchedEntities().size(),
            context.matchedRelations().size(),
            context.matchedChunks().size()
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            assembledContext
        );
    }

    private DirectChunkPass firstDirectChunkPass(QueryRequest query) {
        var embedStartedAt = System.nanoTime();
        var queryVector = embeddingModel.embedAll(List.of(query.query())).get(0);
        var embedMs = elapsedMillis(embedStartedAt);
        var vectorSearchStartedAt = System.nanoTime();
        var matches = VectorSearches.search(
            storageProvider.vectorStore(),
            CHUNK_NAMESPACE,
            queryVector,
            query.query(),
            VectorSearches.mergeKeywords(query.llKeywords(), query.hlKeywords()),
            query.chunkTopK()
        );
        var chunkVectorSearchMs = elapsedMillis(vectorSearchStartedAt);
        var directChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var match : matches) {
            storageProvider.chunkStore().load(match.id()).ifPresent(chunk -> directChunks.merge(
                match.id(),
                new ScoredChunk(match.id(), toChunk(chunk), match.score()),
                (left, right) -> left.score() >= right.score() ? left : right
            ));
        }
        return new DirectChunkPass(queryVector, directChunks, embedMs, chunkVectorSearchMs, query.chunkTopK(), matches.size());
    }

    private MergeOutcome mergeDirectChunkMatches(
        QueryRequest query,
        MetadataFilterPlan metadataPlan,
        QueryContext hybrid,
        DirectChunkPass firstPass
    ) {
        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : hybrid.matchedChunks()) {
            mergedChunks.put(chunk.chunkId(), chunk);
        }
        for (var chunk : firstPass.directChunks().values()) {
            mergedChunks.merge(chunk.chunkId(), chunk, (left, right) -> left.score() >= right.score() ? left : right);
        }

        var searchTopK = firstPass.lastSearchTopK();
        var previousMatchCount = -1;
        long totalVectorSearchMs = firstPass.chunkVectorSearchMs();
        long totalMergeFilterMs = 0L;
        while (true) {
            var mergeFilterStartedAt = System.nanoTime();
            var matchedChunks = QueryMetadataFilterSupport.filterChunks(metadataPlan, mergedChunks.values().stream()
                .sorted(scoreOrder())
                .toList()).stream()
                .limit(query.chunkTopK())
                .toList();
            totalMergeFilterMs += elapsedMillis(mergeFilterStartedAt);
            if (metadataPlan.isEmpty()
                || matchedChunks.size() >= query.chunkTopK()
                || firstPass.lastMatchCount() < searchTopK
                || firstPass.lastMatchCount() <= previousMatchCount
                || searchTopK == Integer.MAX_VALUE) {
                return new MergeOutcome(matchedChunks, totalVectorSearchMs, totalMergeFilterMs);
            }

            previousMatchCount = firstPass.lastMatchCount();
            searchTopK = growSearchTopK(searchTopK);
            var additionalOutcome = searchAdditionalDirectChunkMatches(query, firstPass.queryVector(), searchTopK);
            totalVectorSearchMs += additionalOutcome.chunkVectorSearchMs();
            mergeAdditionalChunks(mergedChunks, additionalOutcome.directChunks());
            firstPass = additionalOutcome;
        }
    }

    private DirectChunkPass searchAdditionalDirectChunkMatches(QueryRequest query, List<Double> queryVector, int searchTopK) {
        var vectorSearchStartedAt = System.nanoTime();
        var matches = VectorSearches.search(
            storageProvider.vectorStore(),
            CHUNK_NAMESPACE,
            queryVector,
            query.query(),
            VectorSearches.mergeKeywords(query.llKeywords(), query.hlKeywords()),
            searchTopK
        );
        var chunkVectorSearchMs = elapsedMillis(vectorSearchStartedAt);
        var directChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var match : matches) {
            storageProvider.chunkStore().load(match.id()).ifPresent(chunk -> directChunks.merge(
                match.id(),
                new ScoredChunk(match.id(), toChunk(chunk), match.score()),
                (left, right) -> left.score() >= right.score() ? left : right
            ));
        }
        return new DirectChunkPass(queryVector, directChunks, 0L, chunkVectorSearchMs, searchTopK, matches.size());
    }

    private static void mergeAdditionalChunks(
        LinkedHashMap<String, ScoredChunk> mergedChunks,
        LinkedHashMap<String, ScoredChunk> additionalChunks
    ) {
        for (var chunk : additionalChunks.values()) {
            mergedChunks.merge(chunk.chunkId(), chunk, (left, right) -> left.score() >= right.score() ? left : right);
        }
    }

    private static int growSearchTopK(int currentTopK) {
        return (int) Math.min(Integer.MAX_VALUE, (long) currentTopK * DIRECT_MATCH_SEARCH_GROWTH_FACTOR);
    }

    private static Chunk toChunk(ChunkStore.ChunkRecord chunk) {
        return new Chunk(
            chunk.id(),
            chunk.documentId(),
            chunk.text(),
            chunk.tokenCount(),
            chunk.order(),
            chunk.metadata()
        );
    }

    private static Comparator<ScoredChunk> scoreOrder() {
        return Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(ScoredChunk::chunkId);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static TimedQueryContext timedRetrieve(QueryStrategy strategy, QueryRequest query) {
        var branchStartedAt = System.nanoTime();
        return new TimedQueryContext(strategy.retrieve(query), elapsedMillis(branchStartedAt));
    }

    private static <T> T awaitBranch(CompletableFuture<T> target, CompletableFuture<?> peer) {
        try {
            return target.join();
        } catch (CompletionException exception) {
            peer.cancel(true);
            throw unwrapCompletionException(exception);
        }
    }

    private static RuntimeException unwrapCompletionException(CompletionException exception) {
        var cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Mix query branch execution failed", cause);
    }

    private record MergeOutcome(List<ScoredChunk> matchedChunks, long chunkVectorSearchMs, long mergeFilterMs) {
    }

    private record TimedQueryContext(QueryContext context, long elapsedMs) {
    }

    private record DirectChunkPass(
        List<Double> queryVector,
        LinkedHashMap<String, ScoredChunk> directChunks,
        long embedMs,
        long chunkVectorSearchMs,
        int lastSearchTopK,
        int lastMatchCount
    ) {
    }
}
