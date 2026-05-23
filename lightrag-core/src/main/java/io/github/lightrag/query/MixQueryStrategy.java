package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.BatchVectorStore;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.OneShotRetrievalStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class MixQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(MixQueryStrategy.class);
    private static final String ENTITY_NAMESPACE = "entities";
    private static final String RELATION_NAMESPACE = "relations";
    private static final String CHUNK_NAMESPACE = "chunks";
    private static final String ENTITY_SEARCH_KEY = "entities";
    private static final String RELATION_SEARCH_KEY = "relations";
    private static final String CHUNK_SEARCH_KEY = "chunks";
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
        if (storageProvider instanceof OneShotRetrievalStore oneShotRetrievalStore
            && oneShotRetrievalStore.supportsMixRetrieval()) {
            return retrieveOneShotMix(query, oneShotRetrievalStore);
        }
        return retrieveBranchingMix(query);
    }

    private QueryContext retrieveBranchingMix(QueryRequest query) {
        var startedAt = System.nanoTime();
        var metadataPlan = QueryMetadataFilterSupport.buildPlan(query);
        var vectorFilter = QueryMetadataFilterSupport.toVectorFilter(metadataPlan);
        var hybridFuture = CompletableFuture.supplyAsync(() -> timedRetrieve(hybridStrategy, query));
        var directChunkFuture = CompletableFuture.supplyAsync(() -> firstDirectChunkPass(query, vectorFilter));
        var hybrid = awaitBranch(hybridFuture, directChunkFuture);
        var directChunkPass = awaitBranch(directChunkFuture, hybridFuture);
        var mergeOutcome = mergeDirectChunkMatches(query, metadataPlan, hybrid.context(), directChunkPass);
        var chunkVectorSearchMs = mergeOutcome.chunkVectorSearchMs();
        var matchedChunks = mergeOutcome.matchedChunks();
        var mergeFilterMs = mergeOutcome.mergeFilterMs();
        var directOneShot = mergeOutcome.directOneShotUsed();
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
            "LightRAG mix retrieve completed: mode={}, query={}, topK={}, chunkTopK={}, directOneShot={}, hybridMs={}, embedMs={}, chunkVectorSearchMs={}, mergeFilterMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            query.topK(),
            query.chunkTopK(),
            directOneShot,
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

    private QueryContext retrieveOneShotMix(QueryRequest query, OneShotRetrievalStore oneShotRetrievalStore) {
        var startedAt = System.nanoTime();
        var metadataPlan = QueryMetadataFilterSupport.buildPlan(query);
        var localEmbeddingText = localEmbeddingText(query);
        var globalEmbeddingText = globalEmbeddingText(query);

        var embedStartedAt = System.nanoTime();
        var embeddings = embedMixTexts(query.query(), localEmbeddingText, globalEmbeddingText);
        var queryVector = embeddings.get(query.query());
        List<Double> localVector = localEmbeddingText == null ? null : embeddings.get(localEmbeddingText);
        List<Double> globalVector = globalEmbeddingText == null ? null : embeddings.get(globalEmbeddingText);
        var embedMs = elapsedMillis(embedStartedAt);

        var vectorSearchStartedAt = System.nanoTime();
        var searchResults = VectorSearches.batchSearch(storageProvider.vectorStore(), mixSearchSpecs(
            query,
            queryVector,
            localEmbeddingText,
            localVector,
            globalEmbeddingText,
            globalVector,
            QueryMetadataFilterSupport.toVectorFilter(metadataPlan),
            query.chunkTopK()
        ));
        var entityMatches = searchResults.getOrDefault(ENTITY_SEARCH_KEY, List.of());
        var relationMatches = searchResults.getOrDefault(RELATION_SEARCH_KEY, List.of());
        var chunkMatches = searchResults.getOrDefault(CHUNK_SEARCH_KEY, List.of());
        var vectorSearchMs = elapsedMillis(vectorSearchStartedAt);

        var graphStartedAt = System.nanoTime();
        var retrieval = oneShotRetrievalStore.retrieveMix(entityMatches, relationMatches, chunkMatches);
        var entities = QueryBudgeting.limitEntities(retrieval.entities(), query.maxEntityTokens());
        var relations = QueryBudgeting.limitRelations(retrieval.relations(), query.maxRelationTokens());
        var graphMs = elapsedMillis(graphStartedAt);

        var mergeStartedAt = System.nanoTime();
        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : retrieval.graphChunks()) {
            mergedChunks.merge(chunk.chunkId(), chunk, (left, right) -> left.score() >= right.score() ? left : right);
        }
        for (var chunk : retrieval.directChunks()) {
            mergedChunks.merge(chunk.chunkId(), chunk, (left, right) -> left.score() >= right.score() ? left : right);
        }
        var matchedChunks = QueryMetadataFilterSupport.filterChunks(metadataPlan, mergedChunks.values().stream()
            .sorted(scoreOrder())
            .toList()).stream()
            .limit(query.chunkTopK())
            .toList();
        var mergeFilterMs = elapsedMillis(mergeStartedAt);

        var context = new QueryContext(entities, relations, matchedChunks, "");
        var assembleStartedAt = System.nanoTime();
        var assembledContext = contextAssembler.assemble(context);
        var assembleMs = elapsedMillis(assembleStartedAt);
        log.info(
            "LightRAG mix retrieve completed: mode={}, query={}, topK={}, chunkTopK={}, oneShotMix={}, directOneShot={}, embedMs={}, vectorSearchMs={}, graphMs={}, mergeFilterMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            query.topK(),
            query.chunkTopK(),
            true,
            true,
            embedMs,
            vectorSearchMs,
            graphMs,
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

    private DirectChunkPass firstDirectChunkPass(QueryRequest query, io.github.lightrag.storage.FilteredVectorStore.MetadataFilter vectorFilter) {
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
            vectorFilter,
            query.chunkTopK()
        );
        var chunkVectorSearchMs = elapsedMillis(vectorSearchStartedAt);
        var directChunks = loadDirectChunks(matches);
        return new DirectChunkPass(queryVector, directChunks.chunks(), directChunks.oneShotUsed(), embedMs, chunkVectorSearchMs, query.chunkTopK(), matches.size());
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
        boolean directOneShotUsed = firstPass.oneShotUsed();
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
                return new MergeOutcome(matchedChunks, totalVectorSearchMs, totalMergeFilterMs, directOneShotUsed);
            }

            previousMatchCount = firstPass.lastMatchCount();
            searchTopK = growSearchTopK(searchTopK);
            var additionalOutcome = searchAdditionalDirectChunkMatches(query, firstPass.queryVector(), QueryMetadataFilterSupport.toVectorFilter(metadataPlan), searchTopK);
            totalVectorSearchMs += additionalOutcome.chunkVectorSearchMs();
            directOneShotUsed = directOneShotUsed || additionalOutcome.oneShotUsed();
            mergeAdditionalChunks(mergedChunks, additionalOutcome.directChunks());
            firstPass = additionalOutcome;
        }
    }

    private DirectChunkPass searchAdditionalDirectChunkMatches(
        QueryRequest query,
        List<Double> queryVector,
        io.github.lightrag.storage.FilteredVectorStore.MetadataFilter vectorFilter,
        int searchTopK
    ) {
        var vectorSearchStartedAt = System.nanoTime();
        var matches = VectorSearches.search(
            storageProvider.vectorStore(),
            CHUNK_NAMESPACE,
            queryVector,
            query.query(),
            VectorSearches.mergeKeywords(query.llKeywords(), query.hlKeywords()),
            vectorFilter,
            searchTopK
        );
        var chunkVectorSearchMs = elapsedMillis(vectorSearchStartedAt);
        var directChunks = loadDirectChunks(matches);
        return new DirectChunkPass(queryVector, directChunks.chunks(), directChunks.oneShotUsed(), 0L, chunkVectorSearchMs, searchTopK, matches.size());
    }

    private DirectChunkLoad loadDirectChunks(List<io.github.lightrag.storage.VectorStore.VectorMatch> matches) {
        var directChunks = new LinkedHashMap<String, ScoredChunk>();
        if (storageProvider instanceof OneShotRetrievalStore oneShotRetrievalStore) {
            for (var chunk : oneShotRetrievalStore.retrieveChunks(matches).chunks()) {
                directChunks.merge(
                    chunk.chunkId(),
                    chunk,
                    (left, right) -> left.score() >= right.score() ? left : right
                );
            }
            return new DirectChunkLoad(directChunks, true);
        }
        var chunksById = storageProvider.chunkStore().loadAll(matches.stream()
            .map(io.github.lightrag.storage.VectorStore.VectorMatch::id)
            .toList());
        for (var match : matches) {
            var chunk = chunksById.get(match.id());
            if (chunk == null) {
                continue;
            }
            directChunks.merge(
                match.id(),
                new ScoredChunk(match.id(), toChunk(chunk), match.score()),
                (left, right) -> left.score() >= right.score() ? left : right
            );
        }
        return new DirectChunkLoad(directChunks, false);
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

    private Map<String, List<Double>> embedMixTexts(String queryText, String localEmbeddingText, String globalEmbeddingText) {
        var texts = new java.util.LinkedHashSet<String>();
        texts.add(queryText);
        if (localEmbeddingText != null) {
            texts.add(localEmbeddingText);
        }
        if (globalEmbeddingText != null) {
            texts.add(globalEmbeddingText);
        }
        var orderedTexts = List.copyOf(texts);
        var vectors = embeddingModel.embedAll(orderedTexts);
        var embeddings = new LinkedHashMap<String, List<Double>>();
        for (int i = 0; i < orderedTexts.size(); i++) {
            embeddings.put(orderedTexts.get(i), vectors.get(i));
        }
        return Map.copyOf(embeddings);
    }

    private static List<BatchVectorStore.SearchSpec> mixSearchSpecs(
        QueryRequest query,
        List<Double> queryVector,
        String localEmbeddingText,
        List<Double> localVector,
        String globalEmbeddingText,
        List<Double> globalVector,
        io.github.lightrag.storage.FilteredVectorStore.MetadataFilter vectorFilter,
        int chunkTopK
    ) {
        var specs = new java.util.ArrayList<BatchVectorStore.SearchSpec>();
        if (localEmbeddingText != null) {
            specs.add(new BatchVectorStore.SearchSpec(
                ENTITY_SEARCH_KEY,
                ENTITY_NAMESPACE,
                localVector,
                query.query(),
                query.llKeywords(),
                vectorFilter,
                query.topK()
            ));
        }
        if (globalEmbeddingText != null) {
            specs.add(new BatchVectorStore.SearchSpec(
                RELATION_SEARCH_KEY,
                RELATION_NAMESPACE,
                globalVector,
                query.query(),
                query.hlKeywords(),
                vectorFilter,
                query.topK()
            ));
        }
        specs.add(new BatchVectorStore.SearchSpec(
            CHUNK_SEARCH_KEY,
            CHUNK_NAMESPACE,
            queryVector,
            query.query(),
            VectorSearches.mergeKeywords(query.llKeywords(), query.hlKeywords()),
            vectorFilter,
            chunkTopK
        ));
        return List.copyOf(specs);
    }

    private static String localEmbeddingText(QueryRequest request) {
        if (!request.llKeywords().isEmpty()) {
            return String.join(", ", request.llKeywords());
        }
        if (!request.hlKeywords().isEmpty()) {
            return null;
        }
        return request.query();
    }

    private static String globalEmbeddingText(QueryRequest request) {
        if (!request.hlKeywords().isEmpty()) {
            return String.join(", ", request.hlKeywords());
        }
        if (!request.llKeywords().isEmpty()) {
            return null;
        }
        return request.query();
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

    private record MergeOutcome(
        List<ScoredChunk> matchedChunks,
        long chunkVectorSearchMs,
        long mergeFilterMs,
        boolean directOneShotUsed
    ) {
    }

    private record TimedQueryContext(QueryContext context, long elapsedMs) {
    }

    private record DirectChunkPass(
        List<Double> queryVector,
        LinkedHashMap<String, ScoredChunk> directChunks,
        boolean oneShotUsed,
        long embedMs,
        long chunkVectorSearchMs,
        int lastSearchTopK,
        int lastMatchCount
    ) {
    }

    private record DirectChunkLoad(LinkedHashMap<String, ScoredChunk> chunks, boolean oneShotUsed) {
    }
}
