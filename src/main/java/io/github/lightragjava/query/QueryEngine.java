package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.api.QueryResult;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.ScoredChunk;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QueryEngine {
    private static final String SYSTEM_PROMPT = "Answer the user's question using the provided context.";

    private final ChatModel chatModel;
    private final ContextAssembler contextAssembler;
    private final Map<QueryMode, QueryStrategy> strategies;
    private final RerankModel rerankModel;

    public QueryEngine(
        ChatModel chatModel,
        ContextAssembler contextAssembler,
        Map<QueryMode, QueryStrategy> strategies,
        RerankModel rerankModel
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.strategies = Map.copyOf(new EnumMap<>(Objects.requireNonNull(strategies, "strategies")));
        this.rerankModel = rerankModel;
    }

    public QueryResult query(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        var strategy = strategies.get(query.mode());
        if (strategy == null) {
            throw new IllegalStateException("No query strategy configured for mode: " + query.mode());
        }

        var retrievalRequest = rerankEnabled(query) ? expandChunkRequest(query) : query;
        var retrievedContext = strategy.retrieve(retrievalRequest);
        var finalChunks = rerankEnabled(query)
            ? rerankChunks(query, retrievedContext.matchedChunks())
            : retrievedContext.matchedChunks();
        var finalContext = new QueryContext(
            retrievedContext.matchedEntities(),
            retrievedContext.matchedRelations(),
            finalChunks,
            ""
        );
        var assembledContext = contextAssembler.assemble(finalContext);
        var assembledQueryContext = new QueryContext(
            finalContext.matchedEntities(),
            finalContext.matchedRelations(),
            finalContext.matchedChunks(),
            assembledContext
        );
        var answer = chatModel.generate(new ChatModel.ChatRequest(
            SYSTEM_PROMPT,
            buildUserPrompt(assembledContext, query),
            query.conversationHistory()
        ));
        return new QueryResult(answer, contextAssembler.toContexts(assembledQueryContext));
    }

    private boolean rerankEnabled(QueryRequest request) {
        return request.enableRerank() && rerankModel != null;
    }

    private static QueryRequest expandChunkRequest(QueryRequest request) {
        long expandedChunkTopK = Math.max((long) request.chunkTopK() * 2L, request.chunkTopK());
        return new QueryRequest(
            request.query(),
            request.mode(),
            request.topK(),
            (int) Math.min(Integer.MAX_VALUE, expandedChunkTopK),
            request.responseType(),
            request.enableRerank(),
            request.userPrompt(),
            request.conversationHistory()
        );
    }

    private static String buildUserPrompt(String assembledContext, QueryRequest query) {
        var basePrompt = """
            Context:
            %s

            Question:
            %s
            """.formatted(assembledContext, query.query());
        if (query.userPrompt().isBlank()) {
            return basePrompt;
        }
        return """
            %s

            Additional Instructions:
            %s
            """.formatted(basePrompt, query.userPrompt());
    }

    private List<ScoredChunk> rerankChunks(QueryRequest request, List<ScoredChunk> matchedChunks) {
        var originalById = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : matchedChunks) {
            originalById.put(chunk.chunkId(), chunk);
        }

        var rerankResults = Objects.requireNonNull(rerankModel, "rerankModel").rerank(new RerankModel.RerankRequest(
            request.query(),
            matchedChunks.stream()
                .map(chunk -> new RerankModel.RerankCandidate(chunk.chunkId(), chunk.chunk().text()))
                .toList()
        ));

        var ordered = new java.util.ArrayList<ScoredChunk>(matchedChunks.size());
        for (var result : rerankResults) {
            var chunk = originalById.remove(result.id());
            if (chunk != null) {
                ordered.add(chunk);
            }
        }
        ordered.addAll(originalById.values());
        return ordered.stream()
            .limit(request.chunkTopK())
            .toList();
    }
}
