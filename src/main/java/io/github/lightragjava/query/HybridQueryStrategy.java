package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.ScoredChunk;
import io.github.lightragjava.types.ScoredEntity;
import io.github.lightragjava.types.ScoredRelation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;

public final class HybridQueryStrategy implements QueryStrategy {
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
        var local = localStrategy.retrieve(request);
        var global = globalStrategy.retrieve(request);

        var mergedEntities = new LinkedHashMap<String, ScoredEntity>();
        for (var entity : local.matchedEntities()) {
            mergedEntities.put(entity.entityId(), entity);
        }
        for (var entity : global.matchedEntities()) {
            mergedEntities.merge(entity.entityId(), entity, HybridQueryStrategy::pickEntity);
        }

        var mergedRelations = new LinkedHashMap<String, ScoredRelation>();
        for (var relation : local.matchedRelations()) {
            mergedRelations.put(relation.relationId(), relation);
        }
        for (var relation : global.matchedRelations()) {
            mergedRelations.merge(relation.relationId(), relation, HybridQueryStrategy::pickRelation);
        }

        var mergedChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : local.matchedChunks()) {
            mergedChunks.put(chunk.chunkId(), chunk);
        }
        for (var chunk : global.matchedChunks()) {
            mergedChunks.merge(chunk.chunkId(), chunk, HybridQueryStrategy::pickChunk);
        }

        var context = new QueryContext(
            QueryBudgeting.limitEntities(mergedEntities.values().stream()
                .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
                .toList(), request.maxEntityTokens()),
            QueryBudgeting.limitRelations(mergedRelations.values().stream()
                .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
                .toList(), request.maxRelationTokens()),
            QueryBudgeting.limitChunks(mergedChunks.values().stream()
                .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
                .limit(request.chunkTopK())
                .toList(), request.maxTotalTokens()),
            ""
        );
        return new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            context.matchedChunks(),
            contextAssembler.assemble(context)
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
}
