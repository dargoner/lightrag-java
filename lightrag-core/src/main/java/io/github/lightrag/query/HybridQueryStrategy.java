package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

        var mergedEntities = ChunkMerges.roundRobinEntities(
            local.context().matchedEntities(),
            global.context().matchedEntities()
        );
        var mergedRelations = ChunkMerges.roundRobinRelations(
            local.context().matchedRelations(),
            global.context().matchedRelations()
        );
        var matchedChunks = QueryMetadataFilterSupport.filterChunks(query, ChunkMerges.roundRobinChunks(List.of(
                local.context().matchedChunks(),
                global.context().matchedChunks()
            ))).stream()
            .limit(query.chunkTopK())
            .toList();
        var context = new QueryContext(
            QueryBudgeting.limitEntities(mergedEntities, query.maxEntityTokens()),
            QueryBudgeting.limitRelations(mergedRelations, query.maxRelationTokens()),
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
