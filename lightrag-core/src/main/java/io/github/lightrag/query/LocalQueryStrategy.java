package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.OneShotRetrievalStore;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class LocalQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(LocalQueryStrategy.class);
    private static final String ENTITY_NAMESPACE = "entities";

    private final EmbeddingModel embeddingModel;
    private final StorageProvider storageProvider;
    private final ContextAssembler contextAssembler;
    private final ParentChunkExpander parentChunkExpander;
    private final QueryBudgeting budgeting;

    public LocalQueryStrategy(
        EmbeddingModel embeddingModel,
        StorageProvider storageProvider,
        ContextAssembler contextAssembler,
        TokenCounter tokenCounter
    ) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.parentChunkExpander = new ParentChunkExpander(storageProvider.chunkStore());
        this.budgeting = new QueryBudgeting(tokenCounter);
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
        var entityMatches = VectorSearches.search(
            storageProvider.vectorStore(),
            ENTITY_NAMESPACE,
            queryVector,
            query.query(),
            query.llKeywords(),
            QueryMetadataFilterSupport.toVectorFilter(metadataPlan),
            query.topK()
        );
        for (var match : entityMatches) {
            entityScores.merge(match.id(), match.score(), Math::max);
        }
        var vectorSearchMs = elapsedMillis(vectorSearchStartedAt);

        var graphStartedAt = System.nanoTime();
        var retrieval = retrieveLocal(entityMatches, entityScores, relationScores);
        var matchedEntities = retrieval.result().entities();
        var matchedRelations = retrieval.result().relations();
        var limitedEntities = budgeting.limitEntities(matchedEntities, query.maxEntityTokens());
        var limitedRelations = budgeting.limitRelations(matchedRelations, query.maxRelationTokens());
        var graphMs = elapsedMillis(graphStartedAt);
        var chunkStartedAt = System.nanoTime();
        var matchedChunks = QueryMetadataFilterSupport.expandAndFilter(
            metadataPlan,
            retrieval.result().chunks().isEmpty()
                ? selectKgChunks(limitedEntities, limitedRelations, query, queryVector)
                : retainChunks(retrieval.result().chunks(), limitedEntities, limitedRelations),
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
            "LightRAG local retrieve completed: mode={}, query={}, embeddingText={}, llKeywords={}, topK={}, chunkTopK={}, oneShot={}, embedMs={}, vectorSearchMs={}, graphMs={}, chunkMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            embeddingText,
            query.llKeywords(),
            query.topK(),
            query.chunkTopK(),
            retrieval.oneShotUsed(),
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

    private LocalRetrieval retrieveLocal(
        List<io.github.lightrag.storage.VectorStore.VectorMatch> entityMatches,
        LinkedHashMap<String, Double> entityScores,
        LinkedHashMap<String, Double> relationScores
    ) {
        if (storageProvider instanceof OneShotRetrievalStore oneShotRetrievalStore) {
            return new LocalRetrieval(oneShotRetrievalStore.retrieveLocal(entityMatches), true);
        }

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
        var scoredRelations = storageProvider.graphStore().loadRelations(List.copyOf(relationScores.keySet())).stream()
            .map(relation -> new ScoredRelation(relation.id(), toRelation(relation), relationScores.getOrDefault(relation.id(), 0.0d)))
            .toList();
        var matchedRelations = rankRelationsByDegreeThenWeight(scoredRelations);
        return new LocalRetrieval(new OneShotRetrievalStore.LocalRetrievalResult(matchedEntities, matchedRelations, List.of()), false);
    }

    /**
     * Upstream ranks local edges by (rank, weight) descending, where rank is the combined
     * incident-relation count of both endpoints (operate.py:6282-6314). Degrees come from one
     * batched graph read; the parent-entity similarity score stays on each relation for display.
     */
    private List<ScoredRelation> rankRelationsByDegreeThenWeight(List<ScoredRelation> relations) {
        if (relations.isEmpty()) {
            return List.of();
        }
        var endpointIds = relations.stream()
            .flatMap(relation -> Stream.of(relation.relation().srcId(), relation.relation().tgtId()))
            .distinct()
            .toList();
        var relationsByEndpointId = storageProvider.graphStore().findRelations(endpointIds);
        return relations.stream()
            .sorted(Comparator
                .<ScoredRelation>comparingInt(relation ->
                    relationsByEndpointId.getOrDefault(relation.relation().srcId(), List.of()).size()
                        + relationsByEndpointId.getOrDefault(relation.relation().tgtId(), List.of()).size())
                .reversed()
                .thenComparing(Comparator.comparingDouble(
                    (ScoredRelation relation) -> relation.relation().weight()).reversed())
                .thenComparing(ScoredRelation::relationId))
            .toList();
    }

    private record LocalRetrieval(OneShotRetrievalStore.LocalRetrievalResult result, boolean oneShotUsed) {
    }

    private static List<ScoredChunk> retainChunks(
        List<ScoredChunk> chunks,
        List<ScoredEntity> matchedEntities,
        List<ScoredRelation> matchedRelations
    ) {
        var chunkIds = new java.util.LinkedHashSet<String>();
        for (var entity : matchedEntities) {
            chunkIds.addAll(entity.entity().sourceChunkIds());
        }
        for (var relation : matchedRelations) {
            chunkIds.addAll(relation.relation().sourceChunkIds());
        }
        return chunks.stream()
            .filter(chunk -> chunkIds.contains(chunk.chunkId()))
            .toList();
    }

    /**
     * Picks KG-related chunks through {@link KgChunkSelector} (upstream
     * {@code _find_related_text_unit_from_entities}) instead of unioning every source
     * chunk of the matched entities and relations. Selected ids are loaded with a
     * single batch call and emitted in selection order.
     */
    private List<ScoredChunk> selectKgChunks(
        List<ScoredEntity> matchedEntities,
        List<ScoredRelation> matchedRelations,
        QueryRequest query,
        List<Double> queryVector
    ) {
        var selection = KgChunkSelector.select(
            query.chunkPickMethod(),
            query.relatedChunkNumber(),
            matchedEntities.stream()
                .map(entity -> new KgChunkSelector.Group(entity.entityId(), entity.entity().sourceChunkIds(), entity.score()))
                .toList(),
            () -> queryVector,
            new VectorStoreChunkVectorRanker(storageProvider.vectorStore())
        );
        log.info(
            "LightRAG local KG chunk selection: requestedMethod={}, method={}, relatedChunkNumber={}, selected={}, candidates={}, frequencyTotal={}",
            query.chunkPickMethod(),
            selection.method(),
            query.relatedChunkNumber(),
            selection.chunkIds().size(),
            selection.frequency().size(),
            selection.frequencyTotal()
        );
        return loadChunksInOrder(selection.chunkIds(), chunkScores(matchedEntities, matchedRelations));
    }

    private static Map<String, Double> chunkScores(
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
        return chunkScores;
    }

    private List<ScoredChunk> loadChunksInOrder(List<String> chunkIds, Map<String, Double> scoreByChunkId) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        var chunksById = storageProvider.chunkStore().loadAll(chunkIds);
        var ordered = new ArrayList<ScoredChunk>(chunkIds.size());
        for (var chunkId : chunkIds) {
            var chunk = chunksById.get(chunkId);
            if (chunk != null) {
                ordered.add(new ScoredChunk(chunkId, toChunk(chunk), scoreByChunkId.getOrDefault(chunkId, 0.0d)));
            }
        }
        return List.copyOf(ordered);
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
            entity.sourceChunkIds(),
            entity.filePath()
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
