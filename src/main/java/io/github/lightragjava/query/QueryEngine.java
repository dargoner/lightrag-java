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
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        Answer the user's question using only the provided context.

        The response should be presented in %s.

        Additional Instructions: %s

        Context:
        %s
        """;

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
        if (query.mode() == QueryMode.BYPASS) {
            return bypassQuery(query);
        }
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
        var contexts = contextAssembler.toContexts(assembledQueryContext);
        var chatRequest = new ChatModel.ChatRequest(
            buildSystemPrompt(assembledContext, query),
            query.query(),
            query.conversationHistory()
        );
        if (query.onlyNeedContext() && !query.onlyNeedPrompt()) {
            return new QueryResult(assembledContext, contexts);
        }
        if (query.onlyNeedPrompt()) {
            return new QueryResult(renderStandardPrompt(chatRequest), contexts);
        }
        var answer = chatModel.generate(chatRequest);
        return new QueryResult(answer, contexts);
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
            request.onlyNeedContext(),
            request.onlyNeedPrompt(),
            request.userPrompt(),
            request.conversationHistory()
        );
    }

    private QueryResult bypassQuery(QueryRequest query) {
        var chatRequest = new ChatModel.ChatRequest(
            "",
            buildBypassUserPrompt(query),
            query.conversationHistory()
        );
        if (query.onlyNeedContext()) {
            return new QueryResult("", List.of());
        }
        if (query.onlyNeedPrompt()) {
            return new QueryResult(renderBypassPrompt(chatRequest), List.of());
        }
        return new QueryResult(chatModel.generate(chatRequest), List.of());
    }

    private static String buildSystemPrompt(String assembledContext, QueryRequest query) {
        return SYSTEM_PROMPT_TEMPLATE.formatted(
            effectiveResponseType(query.responseType()),
            effectiveUserPrompt(query.userPrompt()),
            assembledContext
        );
    }

    private static String buildBypassUserPrompt(QueryRequest query) {
        if (query.userPrompt().isBlank()) {
            return query.query();
        }
        return """
            %s

            Additional Instructions:
            %s
            """.formatted(query.query(), query.userPrompt());
    }

    private static String renderStandardPrompt(ChatModel.ChatRequest request) {
        return """
            %s

            ---User Query---
            %s
            """.formatted(request.systemPrompt(), request.userPrompt());
    }

    private static String renderBypassPrompt(ChatModel.ChatRequest request) {
        var history = request.conversationHistory().isEmpty()
            ? "(none)"
            : request.conversationHistory().stream()
                .map(message -> "- %s: %s".formatted(message.role(), message.content()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
            System Prompt:
            %s

            History:
            %s

            User Prompt:
            %s
            """.formatted(request.systemPrompt(), history, request.userPrompt());
    }

    private static String effectiveResponseType(String responseType) {
        return responseType == null || responseType.isBlank() ? QueryRequest.DEFAULT_RESPONSE_TYPE : responseType;
    }

    private static String effectiveUserPrompt(String userPrompt) {
        return userPrompt == null || userPrompt.isBlank() ? "n/a" : userPrompt;
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
