package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class HybridQueryStrategy implements QueryStrategy {
    private static final Logger log = LoggerFactory.getLogger(HybridQueryStrategy.class);
    private final QueryStrategy localStrategy;
    private final QueryStrategy globalStrategy;
    private final ContextAssembler contextAssembler;

    public HybridQueryStrategy(QueryStrategy localStrategy, QueryStrategy globalStrategy, ContextAssembler contextAssembler) {
        this.localStrategy = Objects.requireNonNull(localStrategy, "localStrategy");
        this.globalStrategy = Objects.requireNonNull(globalStrategy, "globalStrategy");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
    }

    @Override
    public QueryContext retrieve(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var startedAt = System.nanoTime();
        var localFuture = CompletableFuture.supplyAsync(() -> timedRetrieve(localStrategy, query));
        var globalFuture = CompletableFuture.supplyAsync(() -> timedRetrieve(globalStrategy, query));
        var local = awaitBranch(localFuture, globalFuture);
        var global = awaitBranch(globalFuture, localFuture);

        var mergedEntities = new LinkedHashMap<String, ScoredEntity>();
        for (var entity : local.context().matchedEntities()) {
            mergedEntities.put(entity.entityId(), entity);
        }
        for (var entity : global.context().matchedEntities()) {
            mergedEntities.merge(entity.entityId(), entity, HybridQueryStrategy::pickEntity);
        }

        var mergedRelations = new LinkedHashMap<String, ScoredRelation>();
        for (var relation : local.context().matchedRelations()) {
            mergedRelations.put(relation.relationId(), relation);
        }
        for (var relation : global.context().matchedRelations()) {
            mergedRelations.merge(relation.relationId(), relation, HybridQueryStrategy::pickRelation);
        }

        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : local.context().matchedChunks()) {
            mergedChunks.put(chunk.chunkId(), chunk);
        }
        for (var chunk : global.context().matchedChunks()) {
            mergedChunks.merge(chunk.chunkId(), chunk, HybridQueryStrategy::pickChunk);
        }

        var matchedChunks = QueryMetadataFilterSupport.filterChunks(query, mergedChunks.values().stream()
            .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
            .toList()).stream()
            .limit(query.chunkTopK())
            .toList();
        var context = new QueryContext(
            QueryBudgeting.limitEntities(mergedEntities.values().stream()
                .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
                .toList(), query.maxEntityTokens()),
            QueryBudgeting.limitRelations(mergedRelations.values().stream()
                .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
                .toList(), query.maxRelationTokens()),
            matchedChunks,
            ""
        );
        var assembleStartedAt = System.nanoTime();
        var assembledContext = contextAssembler.assemble(context);
        var assembleMs = elapsedMillis(assembleStartedAt);
        var elapsedMs = elapsedMillis(startedAt);
        log.info(
            "LightRAG hybrid retrieve completed: mode={}, query={}, localMs={}, globalMs={}, assembleMs={}, elapsedMs={}, localEntityCount={}, localRelationCount={}, localChunkCount={}, globalEntityCount={}, globalRelationCount={}, globalChunkCount={}, entityCount={}, relationCount={}, chunkCount={}",
            query.mode(),
            query.query(),
            local.elapsedMs(),
            global.elapsedMs(),
            assembleMs,
            elapsedMs,
            local.context().matchedEntities().size(),
            local.context().matchedRelations().size(),
            local.context().matchedChunks().size(),
            global.context().matchedEntities().size(),
            global.context().matchedRelations().size(),
            global.context().matchedChunks().size(),
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

    private static ScoredEntity pickEntity(ScoredEntity left, ScoredEntity right) {
        return left.score() >= right.score() ? left : right;
    }

    private static ScoredRelation pickRelation(ScoredRelation left, ScoredRelation right) {
        return left.score() >= right.score() ? left : right;
    }

    private static ScoredChunk pickChunk(ScoredChunk left, ScoredChunk right) {
        return left.score() >= right.score() ? left : right;
    }

    private static <T> Comparator<T> scoreOrder(
        java.util.function.ToDoubleFunction<T> scoreExtractor,
        java.util.function.Function<T, String> idExtractor
    ) {
        return Comparator.comparingDouble(scoreExtractor).reversed().thenComparing(idExtractor);
    }

    private static TimedQueryContext timedRetrieve(QueryStrategy strategy, QueryRequest query) {
        var branchStartedAt = System.nanoTime();
        return new TimedQueryContext(strategy.retrieve(query), elapsedMillis(branchStartedAt));
    }

    private static TimedQueryContext awaitBranch(
        CompletableFuture<TimedQueryContext> target,
        CompletableFuture<TimedQueryContext> peer
    ) {
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
        return new IllegalStateException("Hybrid query branch execution failed", cause);
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private record TimedQueryContext(QueryContext context, long elapsedMs) {
    }
}
