package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class GlobalQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(GlobalQueryStrategy.class);
    private static final String RELATION_NAMESPACE = "relations";

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final ContextAssembler contextAssembler;
    private final ParentChunkExpander parentChunkExpander;

    public GlobalQueryStrategy(EmbeddingModel embeddingModel, StorageProvider storageProvider, ContextAssembler contextAssembler) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.parentChunkExpander = new ParentChunkExpander(storageProvider.chunkStore());
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var startedAt = System.nanoTime();
        var metadataPlan = QueryMetadataFilterSupport.buildPlan(query);
        var embeddingText = embeddingText(query);
        if (embeddingText == null) {
            return emptyContext();
        }
        var embedStartedAt = System.nanoTime();
        var queryVector = embeddingModel.embedAll(List.of(embeddingText)).get(0);
        var embedMs = elapsedMillis(embedStartedAt);
        var relationScores = new LinkedHashMap<String, Double>();
        var vectorSearchStartedAt = System.nanoTime();
        for (var match : VectorSearches.search(
            storageProvider.vectorStore(),
            RELATION_NAMESPACE,
            queryVector,
            query.query(),
            query.hlKeywords(),
            query.topK()
        )) {
            relationScores.merge(match.id(), match.score(), Math::max);
        }
        var vectorSearchMs = elapsedMillis(vectorSearchStartedAt);

        var graphStartedAt = System.nanoTime();
        var matchedRelations = QueryBudgeting.limitRelations(storageProvider.graphStore().loadRelations(List.copyOf(relationScores.keySet())).stream()
            .map(relation -> new ScoredRelation(relation.id(), toRelation(relation), relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList(), query.maxRelationTokens());

        var entityScores = new LinkedHashMap<String, Double>();
        var chunkScores = new LinkedHashMap<String, Double>();
        for (var relation : matchedRelations) {
            entityScores.merge(relation.relation().sourceEntityId(), relation.score(), Math::max);
            entityScores.merge(relation.relation().targetEntityId(), relation.score(), Math::max);
            for (var chunkId : relation.relation().sourceChunkIds()) {
                chunkScores.merge(chunkId, relation.score(), Math::max);
            }
        }

        var matchedEntities = QueryBudgeting.limitEntities(storageProvider.graphStore().loadEntities(List.copyOf(entityScores.keySet())).stream()
            .map(entity -> new ScoredEntity(entity.id(), toEntity(entity), entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList(), query.maxEntityTokens());
        var graphMs = elapsedMillis(graphStartedAt);
        var chunkStartedAt = System.nanoTime();
        var chunksById = storageProvider.chunkStore().loadAll(List.copyOf(chunkScores.keySet()));
        var matchedChunks = QueryMetadataFilterSupport.expandAndFilter(metadataPlan, chunkScores.entrySet().stream()
            .map(entry -> {
                var chunk = chunksById.get(entry.getKey());
                return chunk == null ? null : new ScoredChunk(entry.getKey(), toChunk(chunk), entry.getValue());
            })
            .filter(Objects::nonNull)
            .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
            .toList(),
            parentChunkExpander,
            query.chunkTopK()
        );
        var chunkMs = elapsedMillis(chunkStartedAt);

        var context = new QueryContext(
            matchedEntities,
            matchedRelations,
            matchedChunks,
            ""
        );
        var assembleStartedAt = System.nanoTime();
        var assembledContext = contextAssembler.assemble(context);
        var assembleMs = elapsedMillis(assembleStartedAt);
        var elapsedMs = elapsedMillis(startedAt);
        log.info(
            "LightRAG global retrieve completed: mode={}, query={}, embedMs={}, vectorSearchMs={}, graphMs={}, chunkMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            embedMs,
            vectorSearchMs,
            graphMs,
            chunkMs,
            assembleMs,
            elapsedMs,
            matchedEntities.size(),
            matchedRelations.size(),
            matchedChunks.size()
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            assembledContext
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

    private QueryContext emptyContext() {
        var context = new QueryContext(List.of(), List.of(), List.of(), "");
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            contextAssembler.assemble(context)
        );
    }

    private static String embeddingText(QueryRequest request) {
        if (!request.hlKeywords().isEmpty()) {
            return String.join(", ", request.hlKeywords());
        }
        if ((request.mode() == io.github.lightrag.api.QueryMode.HYBRID
            || request.mode() == io.github.lightrag.api.QueryMode.MIX)
            && !request.llKeywords().isEmpty()) {
            return null;
        }
        return request.query();
    }

    private static <T> Comparator<T> scoreOrder(
        java.util.function.ToDoubleFunction<T> scoreExtractor,
        java.util.function.Function<T, String> idExtractor
    ) {
        return Comparator.comparingDouble(scoreExtractor).reversed().thenComparing(idExtractor);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
