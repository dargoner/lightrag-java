package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
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
        var relationMatches = VectorSearches.search(
            storageProvider.vectorStore(),
            RELATION_NAMESPACE,
            queryVector,
            query.query(),
            query.hlKeywords(),
            QueryMetadataFilterSupport.toVectorFilter(metadataPlan),
            query.topK()
        );
        for (var match : relationMatches) {
            relationScores.merge(match.id(), match.score(), Math::max);
        }
        var vectorSearchMs = elapsedMillis(vectorSearchStartedAt);

        var graphStartedAt = System.nanoTime();
        var retrieval = retrieveGlobal(relationMatches, relationScores);
        var matchedRelations = QueryBudgeting.limitRelations(retrieval.result().relations(), query.maxRelationTokens());
        var matchedEntities = QueryBudgeting.limitEntities(retrieval.result().entities(), query.maxEntityTokens());
        var graphMs = elapsedMillis(graphStartedAt);
        var chunkStartedAt = System.nanoTime();
        var matchedChunks = QueryMetadataFilterSupport.expandAndFilter(metadataPlan,
            retrieval.result().chunks().isEmpty()
                ? collectChunks(matchedRelations)
                : retainChunks(retrieval.result().chunks(), matchedRelations),
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
            "LightRAG global retrieve completed: mode={}, query={}, embeddingText={}, hlKeywords={}, topK={}, chunkTopK={}, oneShot={}, embedMs={}, vectorSearchMs={}, graphMs={}, chunkMs={}, assembleMs={}, elapsedMs={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            embeddingText,
            query.hlKeywords(),
            query.topK(),
            query.chunkTopK(),
            retrieval.oneShotUsed(),
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

    private GlobalRetrieval retrieveGlobal(
        List<io.github.lightrag.storage.VectorStore.VectorMatch> relationMatches,
        LinkedHashMap<String, Double> relationScores
    ) {
        if (storageProvider instanceof OneShotRetrievalStore oneShotRetrievalStore) {
            return new GlobalRetrieval(oneShotRetrievalStore.retrieveGlobal(relationMatches), true);
        }

        var matchedRelations = storageProvider.graphStore().loadRelations(List.copyOf(relationScores.keySet())).stream()
            .map(relation -> new ScoredRelation(relation.id(), toRelation(relation), relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();

        var entityScores = new LinkedHashMap<String, Double>();
        for (var relation : matchedRelations) {
            entityScores.merge(relation.relation().srcId(), relation.score(), Math::max);
            entityScores.merge(relation.relation().tgtId(), relation.score(), Math::max);
        }

        var matchedEntities = storageProvider.graphStore().loadEntities(List.copyOf(entityScores.keySet())).stream()
            .map(entity -> new ScoredEntity(entity.id(), toEntity(entity), entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        return new GlobalRetrieval(new OneShotRetrievalStore.GlobalRetrievalResult(matchedEntities, matchedRelations, List.of()), false);
    }

    private record GlobalRetrieval(OneShotRetrievalStore.GlobalRetrievalResult result, boolean oneShotUsed) {
    }

    private List<ScoredChunk> collectChunks(List<ScoredRelation> matchedRelations) {
        var chunkScores = new LinkedHashMap<String, Double>();
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

    private static List<ScoredChunk> retainChunks(List<ScoredChunk> chunks, List<ScoredRelation> matchedRelations) {
        var chunkIds = new java.util.LinkedHashSet<String>();
        for (var relation : matchedRelations) {
            chunkIds.addAll(relation.relation().sourceChunkIds());
        }
        return chunks.stream()
            .filter(chunk -> chunkIds.contains(chunk.chunkId()))
            .toList();
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
