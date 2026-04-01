package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class NaiveQueryStrategy implements QueryStrategy {
    private static final String CHUNK_NAMESPACE = "chunks";

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final ContextAssembler contextAssembler;
    private final ParentChunkExpander parentChunkExpander;

    public NaiveQueryStrategy(
        EmbeddingModel embeddingModel,
        StorageProvider storageProvider,
        ContextAssembler contextAssembler
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.parentChunkExpander = new ParentChunkExpander(storageProvider.chunkStore());
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var queryVector = embeddingModel.embedAll(List.of(query.query())).get(0);
        var matchedChunks = VectorSearches.search(
                storageProvider.vectorStore(),
                CHUNK_NAMESPACE,
                queryVector,
                query.query(),
                List.of(),
                query.chunkTopK()
            ).stream()
            .map(match -> storageProvider.chunkStore().load(match.id())
                .map(chunk -> new ScoredChunk(match.id(), toChunk(chunk), match.score()))
                .orElse(null))
            .filter(Objects::nonNull)
            .sorted(scoreOrder())
            .toList();
        var expandedChunks = parentChunkExpander.expand(matchedChunks, query.chunkTopK());
        var context = new QueryContext(List.of(), List.of(), expandedChunks, "");
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
