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
import java.util.Map;
import java.util.Objects;

public final class LocalQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(LocalQueryStrategy.class);
    private static final String ENTITY_NAMESPACE = "entities";

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final ContextAssembler contextAssembler;
    private final ParentChunkExpander parentChunkExpander;

    public LocalQueryStrategy(EmbeddingModel embeddingModel, StorageProvider storageProvider, ContextAssembler contextAssembler) {
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
        var queryVector = embed(embeddingText);
        var embedMs = elapsedMillis(embedStartedAt);
        var entityScores = new LinkedHashMap<String, Double>();
        var relationScores = new LinkedHashMap<String, Double>();

        var vectorSearchStartedAt = System.nanoTime();
        for (var match : VectorSearches.search(
            storageProvider.vectorStore(),
            ENTITY_NAMESPACE,
            queryVector,
            query.query(),
            query.llKeywords(),
            query.topK()
        )) {
            entityScores.merge(match.id(), match.score(), Math::max);
        }
        var vectorSearchMs = elapsedMillis(vectorSearchStartedAt);

        var graphStartedAt = System.nanoTime();
        var relationsByEntityId = storageProvider.graphStore().findRelations(List.copyOf(entityScores.keySet()));
        for (var entityId : List.copyOf(entityScores.keySet())) {
            for (var relationRecord : relationsByEntityId.getOrDefault(entityId, List.of())) {
                var relationScore = entityScores.getOrDefault(entityId, 0.0d);
                relationScores.merge(relationRecord.id(), relationScore, Math::max);
                entityScores.merge(relationRecord.srcId(), relationScore, Math::max);
                entityScores.merge(relationRecord.tgtId(), relationScore, Math::max);
            }
        }

        var matchedEntities = storageProvider.graphStore().loadEntities(List.copyOf(entityScores.keySet())).stream()
            .map(entity -> new ScoredEntity(entity.id(), toEntity(entity), entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        var matchedRelations = storageProvider.graphStore().loadRelations(List.copyOf(relationScores.keySet())).stream()
            .map(relation -> new ScoredRelation(relation.id(), toRelation(relation), relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();
        var limitedEntities = QueryBudgeting.limitEntities(matchedEntities, query.maxEntityTokens());
        var limitedRelations = QueryBudgeting.limitRelations(matchedRelations, query.maxRelationTokens());
        var graphMs = elapsedMillis(graphStartedAt);
        var chunkStartedAt = System.nanoTime();
        var matchedChunks = QueryMetadataFilterSupport.expandAndFilter(
            metadataPlan,
            collectChunks(limitedEntities, limitedRelations),
            parentChunkExpander,
            query.chunkTopK()
        );
        var chunkMs = elapsedMillis(chunkStartedAt);

        var context = new QueryContext(
            limitedEntities,
            limitedRelations,
            matchedChunks,
            ""
        );
        var assembleStartedAt = System.nanoTime();
        var assembledContext = contextAssembler.assemble(context);
        var assembleMs = elapsedMillis(assembleStartedAt);
        var elapsedMs = elapsedMillis(startedAt);
        log.info(
            "LightRAG local retrieve completed: mode={}, query={}, embeddingText={}, llKeywords={}, topK={}, chunkTopK={}, embedMs={}, vectorSearchMs={}, graphMs={}, chunkMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            embeddingText,
            query.llKeywords(),
            query.topK(),
            query.chunkTopK(),
            embedMs,
            vectorSearchMs,
            graphMs,
            chunkMs,
            assembleMs,
            elapsedMs,
            limitedEntities.size(),
            limitedRelations.size(),
            matchedChunks.size()
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            assembledContext
        );
    }

    private List<ScoredChunk> collectChunks(
        List<ScoredEntity> matchedEntities,
        List<ScoredRelation> matchedRelations
    ) {
        var chunkScores = new LinkedHashMap<String, Double>();
        for (var entity : matchedEntities) {
            for (var chunkId : entity.entity().sourceChunkIds()) {
                chunkScores.merge(chunkId, entity.score(), Math::max);
            }
        }
        for (var relation : matchedRelations) {
            for (var chunkId : relation.relation().sourceChunkIds()) {
                chunkScores.merge(chunkId, relation.score(), Math::max);
            }
        }

        var chunksById = storageProvider.chunkStore().loadAll(List.copyOf(chunkScores.keySet()));
        return chunkScores.entrySet().stream()
            .map(entry -> {
                var chunk = chunksById.get(entry.getKey());
                return chunk == null ? null : new ScoredChunk(entry.getKey(), toChunk(chunk), entry.getValue());
            })
            .filter(Objects::nonNull)
            .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
            .toList();
    }

    private List<Double> embed(String query) {
        return embeddingModel.embedAll(List.of(query)).get(0);
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
        if (!request.llKeywords().isEmpty()) {
            return String.join(", ", request.llKeywords());
        }
        if ((request.mode() == io.github.lightrag.api.QueryMode.HYBRID
            || request.mode() == io.github.lightrag.api.QueryMode.MIX)
            && !request.hlKeywords().isEmpty()) {
            return null;
        }
        return request.query();
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
            relation.srcId(),
            relation.tgtId(),
            relation.keywords(),
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

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }
}
