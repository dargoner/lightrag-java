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
    private static final int CHUNK_BUDGET_BUFFER_TOKENS = 16;

    private static final String GRAPH_SYSTEM_PROMPT_TEMPLATE = """
        ---Role---

        You are an expert AI assistant specializing in synthesizing information from a provided knowledge base. Your primary function is to answer user queries accurately by ONLY using the information within the provided **Context**.

        ---Goal---

        Generate a comprehensive, well-structured answer to the user query.
        The answer must integrate relevant facts from the Knowledge Graph Data and Document Chunks found in the **Context**.
        Consider the conversation history if provided to maintain conversational flow and avoid repeating information.

        ---Instructions---

        1. Step-by-Step Instruction:
          - Carefully determine the user's query intent in the context of the conversation history to fully understand the user's information need.
          - Scrutinize both `Knowledge Graph Data` and `Document Chunks` in the **Context**. Identify and extract all pieces of information that are directly relevant to answering the user query.
          - Weave the extracted facts into a coherent and logical response. Your own knowledge must ONLY be used to formulate fluent sentences and connect ideas, NOT to introduce any external information.
          - When the context includes usable source metadata, generate a references section at the end of the response. Do not invent references that are not grounded in the context.
          - Do not generate anything after the references section.

        2. Content & Grounding:
          - Strictly adhere to the provided context from the **Context**; DO NOT invent, assume, or infer any information not explicitly stated.
          - If the answer cannot be found in the **Context**, state that you do not have enough information to answer. Do not attempt to guess.

        3. Formatting & Language:
          - The response MUST be in the same language as the user query.
          - The response MUST utilize Markdown formatting for enhanced clarity and structure (for example: headings, bold text, bullet points).
          - The response should be presented in %s.

        4. References Section Format:
          - If references are supported by the context, place them under the heading `### References`.
          - Do not invent citations, footnotes, or trailing commentary after the references section.

        5. Additional Instructions: %s

        ---Context---

        %s
        """;

    private static final String NAIVE_SYSTEM_PROMPT_TEMPLATE = """
        ---Role---

        You are an expert AI assistant specializing in synthesizing information from a provided knowledge base. Your primary function is to answer user queries accurately by ONLY using the information within the provided **Context**.

        ---Goal---

        Generate a comprehensive, well-structured answer to the user query.
        The answer must integrate relevant facts from the Document Chunks found in the **Context**.
        Consider the conversation history if provided to maintain conversational flow and avoid repeating information.

        ---Instructions---

        1. Step-by-Step Instruction:
          - Carefully determine the user's query intent in the context of the conversation history to fully understand the user's information need.
          - Scrutinize `Document Chunks` in the **Context**. Identify and extract all pieces of information that are directly relevant to answering the user query.
          - Weave the extracted facts into a coherent and logical response. Your own knowledge must ONLY be used to formulate fluent sentences and connect ideas, NOT to introduce any external information.
          - When the context includes usable source metadata, generate a references section at the end of the response. Do not invent references that are not grounded in the context.
          - Do not generate anything after the references section.

        2. Content & Grounding:
          - Strictly adhere to the provided context from the **Context**; DO NOT invent, assume, or infer any information not explicitly stated.
          - If the answer cannot be found in the **Context**, state that you do not have enough information to answer. Do not attempt to guess.

        3. Formatting & Language:
          - The response MUST be in the same language as the user query.
          - The response MUST utilize Markdown formatting for enhanced clarity and structure (for example: headings, bold text, bullet points).
          - The response should be presented in %s.

        4. References Section Format:
          - If references are supported by the context, place them under the heading `### References`.
          - Do not invent citations, footnotes, or trailing commentary after the references section.

        5. Additional Instructions: %s

        ---Context---

        %s
        """;

    private final ChatModel chatModel;
    private final ContextAssembler contextAssembler;
    private final Map<QueryMode, QueryStrategy> strategies;
    private final RerankModel rerankModel;
    private final QueryKeywordExtractor keywordExtractor;

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
        this.keywordExtractor = new QueryKeywordExtractor();
    }

    public QueryResult query(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        if (query.mode() == QueryMode.BYPASS) {
            return bypassQuery(query);
        }
        var responseModel = selectChatModel(query);
        var resolvedQuery = keywordExtractor.resolve(query, responseModel);
        var strategy = strategies.get(resolvedQuery.mode());
        if (strategy == null) {
            throw new IllegalStateException("No query strategy configured for mode: " + resolvedQuery.mode());
        }

        var retrievalRequest = rerankEnabled(resolvedQuery) ? expandChunkRequest(resolvedQuery) : resolvedQuery;
        var retrievedContext = strategy.retrieve(retrievalRequest);
        var rerankedChunks = rerankEnabled(resolvedQuery)
            ? rerankChunks(resolvedQuery, retrievedContext.matchedChunks())
            : retrievedContext.matchedChunks();
        var finalChunks = QueryBudgeting.limitChunks(
            rerankedChunks,
            remainingChunkBudget(resolvedQuery, retrievedContext)
        );
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
        var references = QueryReferences.fromChunks(assembledQueryContext.matchedChunks(), resolvedQuery.includeReferences());
        var chatRequest = new ChatModel.ChatRequest(
            buildSystemPrompt(resolvedQuery, assembledContext),
            resolvedQuery.query(),
            resolvedQuery.conversationHistory()
        );
        if (resolvedQuery.onlyNeedContext() && !resolvedQuery.onlyNeedPrompt()) {
            return new QueryResult(assembledContext, references.contexts(), references.references());
        }
        if (resolvedQuery.onlyNeedPrompt()) {
            return new QueryResult(renderStandardPrompt(chatRequest), references.contexts(), references.references());
        }
        if (resolvedQuery.stream()) {
            return QueryResult.streaming(responseModel.stream(chatRequest), references.contexts(), references.references());
        }
        var answer = responseModel.generate(chatRequest);
        return new QueryResult(answer, references.contexts(), references.references());
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
            request.maxEntityTokens(),
            request.maxRelationTokens(),
            Integer.MAX_VALUE,
            request.responseType(),
            request.enableRerank(),
            request.onlyNeedContext(),
            request.onlyNeedPrompt(),
            request.includeReferences(),
            request.stream(),
            request.modelFunc(),
            request.userPrompt(),
            request.hlKeywords(),
            request.llKeywords(),
            request.conversationHistory()
        );
    }

    private QueryResult bypassQuery(QueryRequest query) {
        var chatRequest = new ChatModel.ChatRequest(
            "",
            buildBypassUserPrompt(query),
            query.conversationHistory()
        );
        var responseModel = selectChatModel(query);
        if (query.onlyNeedContext()) {
            return new QueryResult("", List.of(), List.of());
        }
        if (query.onlyNeedPrompt()) {
            return new QueryResult(renderBypassPrompt(chatRequest), List.of(), List.of());
        }
        if (query.stream()) {
            return QueryResult.streaming(responseModel.stream(chatRequest), List.of(), List.of());
        }
        return new QueryResult(responseModel.generate(chatRequest), List.of(), List.of());
    }

    private ChatModel selectChatModel(QueryRequest request) {
        return request.modelFunc() != null ? request.modelFunc() : chatModel;
    }

    private static String buildSystemPrompt(QueryRequest query, String assembledContext) {
        return systemPromptTemplate(query.mode()).formatted(
            effectiveResponseType(query.responseType()),
            effectiveUserPrompt(query.userPrompt()),
            assembledContext
        );
    }

    private static String systemPromptTemplate(QueryMode mode) {
        if (mode == QueryMode.NAIVE) {
            return NAIVE_SYSTEM_PROMPT_TEMPLATE;
        }
        return GRAPH_SYSTEM_PROMPT_TEMPLATE;
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

    private int remainingChunkBudget(QueryRequest request, QueryContext context) {
        var nonChunkContext = new QueryContext(
            context.matchedEntities(),
            context.matchedRelations(),
            List.of(),
            ""
        );
        var assembledNonChunkContext = contextAssembler.assemble(nonChunkContext);
        var systemPromptTokens = QueryBudgeting.approximateTokenCount(buildSystemPrompt(request, assembledNonChunkContext));
        var queryTokens = QueryBudgeting.approximateTokenCount(request.query());
        long remaining = (long) request.maxTotalTokens() - systemPromptTokens - queryTokens - CHUNK_BUDGET_BUFFER_TOKENS;
        return (int) Math.max(0L, remaining);
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
