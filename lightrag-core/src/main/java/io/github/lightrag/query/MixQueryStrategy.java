package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class MixQueryStrategy implements QueryStrategy {
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
        var metadataPlan = QueryMetadataFilterSupport.buildPlan(query);
        var hybrid = hybridStrategy.retrieve(query);
        var queryVector = embeddingModel.embedAll(List.of(query.query())).get(0);
        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : hybrid.matchedChunks()) {
            mergedChunks.put(chunk.chunkId(), chunk);
        }
        var matchedChunks = mergeDirectChunkMatches(query, metadataPlan, queryVector, mergedChunks);
        var context = new QueryContext(
            QueryBudgeting.limitEntities(hybrid.matchedEntities(), query.maxEntityTokens()),
            QueryBudgeting.limitRelations(hybrid.matchedRelations(), query.maxRelationTokens()),
            matchedChunks,
            ""
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            contextAssembler.assemble(context)
        );
    }

    private List<ScoredChunk> mergeDirectChunkMatches(
        QueryRequest query,
        MetadataFilterPlan metadataPlan,
        List<Double> queryVector,
        LinkedHashMap<String, ScoredChunk> mergedChunks
    ) {
        var searchTopK = query.chunkTopK();
        var previousMatchCount = -1;
        while (true) {
            var matches = VectorSearches.search(
                storageProvider.vectorStore(),
                CHUNK_NAMESPACE,
                queryVector,
                query.query(),
                VectorSearches.mergeKeywords(query.llKeywords(), query.hlKeywords()),
                searchTopK
            );
            for (var match : matches) {
                storageProvider.chunkStore().load(match.id()).ifPresent(chunk -> mergedChunks.merge(
                    match.id(),
                    new ScoredChunk(match.id(), toChunk(chunk), match.score()),
                    (left, right) -> left.score() >= right.score() ? left : right
                ));
            }

            var matchedChunks = QueryMetadataFilterSupport.filterChunks(metadataPlan, mergedChunks.values().stream()
                .sorted(scoreOrder())
                .toList()).stream()
                .limit(query.chunkTopK())
                .toList();
            if (metadataPlan.isEmpty()
                || matchedChunks.size() >= query.chunkTopK()
                || matches.size() < searchTopK
                || matches.size() <= previousMatchCount
                || searchTopK == Integer.MAX_VALUE) {
                return matchedChunks;
            }

            previousMatchCount = matches.size();
            searchTopK = growSearchTopK(searchTopK);
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
}
