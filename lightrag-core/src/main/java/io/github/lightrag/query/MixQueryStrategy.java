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
        var hybrid = hybridStrategy.retrieve(request);
        var queryVector = embeddingModel.embedAll(List.of(request.query())).get(0);
        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : hybrid.matchedChunks()) {
            mergedChunks.put(chunk.chunkId(), chunk);
        }
        for (var match : storageProvider.vectorStore().search(CHUNK_NAMESPACE, queryVector, request.chunkTopK())) {
            storageProvider.chunkStore().load(match.id()).ifPresent(chunk -> mergedChunks.merge(
                match.id(),
                new ScoredChunk(match.id(), toChunk(chunk), match.score()),
                (left, right) -> left.score() >= right.score() ? left : right
            ));
        }

        var context = new QueryContext(
            QueryBudgeting.limitEntities(hybrid.matchedEntities(), request.maxEntityTokens()),
            QueryBudgeting.limitRelations(hybrid.matchedRelations(), request.maxRelationTokens()),
            mergedChunks.values().stream()
                .sorted(scoreOrder())
                .limit(request.chunkTopK())
                .toList(),
            ""
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            contextAssembler.assemble(context)
        );
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
