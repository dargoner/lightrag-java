package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.Relation;
import io.github.lightragjava.types.ScoredChunk;
import io.github.lightragjava.types.ScoredEntity;
import io.github.lightragjava.types.ScoredRelation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class GlobalQueryStrategy implements QueryStrategy {
    private static final String RELATION_NAMESPACE = "relations";

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final ContextAssembler contextAssembler;

    public GlobalQueryStrategy(EmbeddingModel embeddingModel, StorageProvider storageProvider, ContextAssembler contextAssembler) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var queryVector = embeddingModel.embedAll(List.of(query.query())).getFirst();
        var relationScores = new LinkedHashMap<String, Double>();
        var entityScores = new LinkedHashMap<String, Double>();
        var chunkScores = new LinkedHashMap<String, Double>();

        for (var match : storageProvider.vectorStore().search(RELATION_NAMESPACE, queryVector, query.topK())) {
            relationScores.merge(match.id(), match.score(), Math::max);
        }

        var matchedRelations = relationScores.entrySet().stream()
            .map(entry -> storageProvider.graphStore().loadRelation(entry.getKey())
                .map(relation -> {
                    entityScores.merge(relation.sourceEntityId(), entry.getValue(), Math::max);
                    entityScores.merge(relation.targetEntityId(), entry.getValue(), Math::max);
                    for (var chunkId : relation.sourceChunkIds()) {
                        chunkScores.merge(chunkId, entry.getValue(), Math::max);
                    }
                    return new ScoredRelation(entry.getKey(), toRelation(relation), entry.getValue());
                })
                .orElse(null))
            .filter(Objects::nonNull)
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();

        var matchedEntities = entityScores.entrySet().stream()
            .map(entry -> storageProvider.graphStore().loadEntity(entry.getKey())
                .map(entity -> new ScoredEntity(entry.getKey(), toEntity(entity), entry.getValue()))
                .orElse(null))
            .filter(Objects::nonNull)
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        var matchedChunks = chunkScores.entrySet().stream()
            .map(entry -> storageProvider.chunkStore().load(entry.getKey())
                .map(chunk -> new ScoredChunk(entry.getKey(), toChunk(chunk), entry.getValue()))
                .orElse(null))
            .filter(Objects::nonNull)
            .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
            .limit(query.chunkTopK())
            .toList();

        var context = new QueryContext(
            matchedEntities,
            matchedRelations,
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

    private static Entity toEntity(GraphStore.EntityRecord entity) {
        return new Entity(
            entity.id(),
            entity.name(),
            entity.type(),
            entity.description(),
            entity.aliases(),
            entity.sourceChunkIds()
        );
    }

    private static Relation toRelation(GraphStore.RelationRecord relation) {
        return new Relation(
            relation.id(),
            relation.sourceEntityId(),
            relation.targetEntityId(),
            relation.type(),
            relation.description(),
            relation.weight(),
            relation.sourceChunkIds()
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

    private static <T> Comparator<T> scoreOrder(
        java.util.function.ToDoubleFunction<T> scoreExtractor,
        java.util.function.Function<T, String> idExtractor
    ) {
        return Comparator.comparingDouble(scoreExtractor).reversed().thenComparing(idExtractor);
    }
}
