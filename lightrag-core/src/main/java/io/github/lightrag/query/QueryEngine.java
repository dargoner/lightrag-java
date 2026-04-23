package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.api.QueryResult;
import io.github.lightrag.api.StructuredQueryChunk;
import io.github.lightrag.api.StructuredQueryEntity;
import io.github.lightrag.api.StructuredQueryRelation;
import io.github.lightrag.api.StructuredQueryResult;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.synthesis.PathAwareAnswerSynthesizer;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QueryEngine {
    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);
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
    private final int rerankCandidateMultiplier;
    private final QueryIntentClassifier queryIntentClassifier;
    private final QueryStrategy multiHopStrategy;
    private final PathAwareAnswerSynthesizer pathAwareAnswerSynthesizer;

    public QueryEngine(
        ChatModel chatModel,
        ContextAssembler contextAssembler,
        Map<QueryMode, QueryStrategy> strategies,
        RerankModel rerankModel
    ) {
        this(chatModel, contextAssembler, strategies, rerankModel, true, 2);
    }

    public QueryEngine(
        ChatModel chatModel,
        ContextAssembler contextAssembler,
        Map<QueryMode, QueryStrategy> strategies,
        RerankModel rerankModel,
        boolean automaticKeywordExtractionEnabled,
        int rerankCandidateMultiplier
    ) {
        this(
            chatModel,
            contextAssembler,
            strategies,
            rerankModel,
            automaticKeywordExtractionEnabled,
            rerankCandidateMultiplier,
            null,
            null,
            new PathAwareAnswerSynthesizer()
        );
    }

    public QueryEngine(
        ChatModel chatModel,
        ContextAssembler contextAssembler,
        Map<QueryMode, QueryStrategy> strategies,
        RerankModel rerankModel,
        boolean automaticKeywordExtractionEnabled,
        int rerankCandidateMultiplier,
        QueryIntentClassifier queryIntentClassifier,
        QueryStrategy multiHopStrategy,
        PathAwareAnswerSynthesizer pathAwareAnswerSynthesizer
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.contextAssembler = Objects.requireNonNull(contextAssembler, "contextAssembler");
        this.strategies = Map.copyOf(new EnumMap<>(Objects.requireNonNull(strategies, "strategies")));
        this.rerankModel = rerankModel;
        if (rerankCandidateMultiplier <= 0) {
            throw new IllegalArgumentException("rerankCandidateMultiplier must be positive");
        }
        this.keywordExtractor = new QueryKeywordExtractor(automaticKeywordExtractionEnabled);
        this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        this.queryIntentClassifier = queryIntentClassifier;
        this.multiHopStrategy = multiHopStrategy;
        this.pathAwareAnswerSynthesizer = Objects.requireNonNull(pathAwareAnswerSynthesizer, "pathAwareAnswerSynthesizer");
    }

    public QueryResult query(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        if (query.mode() == QueryMode.BYPASS) {
            return bypassQuery(query);
        }
        var execution = executeStandardQuery(query);
        if (execution.resolvedQuery().onlyNeedContext() && !execution.resolvedQuery().onlyNeedPrompt()) {
            return new QueryResult(
                execution.queryContext().assembledContext(),
                execution.references().contexts(),
                execution.references().references()
            );
        }
        if (execution.resolvedQuery().onlyNeedPrompt()) {
            return new QueryResult(
                renderStandardPrompt(execution.chatRequest()),
                execution.references().contexts(),
                execution.references().references()
            );
        }
        if (execution.resolvedQuery().stream()) {
            return QueryResult.streaming(
                execution.responseModel().stream(execution.chatRequest()),
                execution.references().contexts(),
                execution.references().references()
            );
        }
        return new QueryResult(
            generateStandardAnswer(execution),
            execution.references().contexts(),
            execution.references().references()
        );
    }

    public StructuredQueryResult queryStructured(QueryRequest request) {
        var query = Objects.requireNonNull(request, "request");
        if (query.stream()) {
            throw new IllegalArgumentException("queryStructured does not support stream=true");
        }
        if (query.mode() == QueryMode.BYPASS) {
            return bypassStructuredQuery(query);
        }
        var execution = executeStandardQuery(query);
        return new StructuredQueryResult(
            resolveStructuredAnswer(execution),
            execution.references().contexts(),
            execution.references().references(),
            execution.queryContext().matchedEntities().stream()
                .map(QueryEngine::toStructuredEntity)
                .toList(),
            execution.queryContext().matchedRelations().stream()
                .map(QueryEngine::toStructuredRelation)
                .toList(),
            execution.queryContext().matchedChunks().stream()
                .map(QueryEngine::toStructuredChunk)
                .toList()
        );
    }

    private String generateTwoStageAnswer(ChatModel responseModel, ChatModel.ChatRequest baseRequest) {
        var reasoningDraft = responseModel.generate(new ChatModel.ChatRequest(
            pathAwareAnswerSynthesizer.buildReasoningStagePrompt(baseRequest.systemPrompt()),
            baseRequest.userPrompt(),
            baseRequest.conversationHistory()
        ));
        return responseModel.generate(new ChatModel.ChatRequest(
            pathAwareAnswerSynthesizer.buildFinalStagePrompt(baseRequest.systemPrompt(), reasoningDraft),
            baseRequest.userPrompt(),
            baseRequest.conversationHistory()
        ));
    }

    private boolean rerankEnabled(QueryRequest request) {
        return request.enableRerank() && rerankModel != null;
    }

    private static QueryRequest expandChunkRequest(QueryRequest request, int rerankCandidateMultiplier) {
        long expandedChunkTopK = Math.max((long) request.chunkTopK() * rerankCandidateMultiplier, request.chunkTopK());
        return new QueryRequest(
            request.query(),
            request.mode(),
            request.topK(),
            (int) Math.min(Integer.MAX_VALUE, expandedChunkTopK),
            request.maxEntityTokens(),
            request.maxRelationTokens(),
            Integer.MAX_VALUE,
            request.maxHop(),
            request.pathTopK(),
            request.multiHopEnabled(),
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
            request.conversationHistory(),
            request.metadataFilters(),
            request.metadataConditions()
        );
    }

    private boolean shouldUseMultiHop(QueryRequest request) {
        return request.multiHopEnabled()
            && request.mode() != QueryMode.NAIVE
            && request.mode() != QueryMode.BYPASS
            && queryIntentClassifier != null
            && multiHopStrategy != null
            && queryIntentClassifier.classify(request) == QueryIntent.MULTI_HOP;
    }

    private QueryExecution executeStandardQuery(QueryRequest query) {
        var startedAt = System.nanoTime();
        var responseModel = selectChatModel(query);
        var keywordStartedAt = System.nanoTime();
        var resolvedQuery = keywordExtractor.resolve(query, responseModel);
        var keywordMs = elapsedMillis(keywordStartedAt);
        var useMultiHop = shouldUseMultiHop(resolvedQuery);
        var strategy = useMultiHop ? multiHopStrategy : strategies.get(resolvedQuery.mode());
        if (strategy == null) {
            throw new IllegalStateException("No query strategy configured for mode: " + resolvedQuery.mode());
        }
        log.info(
            "LightRAG query resolved: requestedMode={}, resolvedMode={}, query={}, topK={}, chunkTopK={}, hlKeywords={}, llKeywords={}, useMultiHop={}, rerankRequested={}, rerankActive={}",
            query.mode(),
            resolvedQuery.mode(),
            resolvedQuery.query(),
            resolvedQuery.topK(),
            resolvedQuery.chunkTopK(),
            resolvedQuery.hlKeywords(),
            resolvedQuery.llKeywords(),
            useMultiHop,
            resolvedQuery.enableRerank(),
            rerankEnabled(resolvedQuery) && !useMultiHop
        );

        var retrievalRequest = rerankEnabled(resolvedQuery) && !useMultiHop
            ? expandChunkRequest(resolvedQuery, rerankCandidateMultiplier)
            : resolvedQuery;
        var retrieveStartedAt = System.nanoTime();
        var retrievedContext = strategy.retrieve(retrievalRequest);
        var retrieveMs = elapsedMillis(retrieveStartedAt);
        var rerankStartedAt = System.nanoTime();
        var rerankedChunks = rerankEnabled(resolvedQuery) && !useMultiHop
            ? rerankChunks(resolvedQuery, retrievedContext.matchedChunks())
            : retrievedContext.matchedChunks();
        var rerankMs = rerankEnabled(resolvedQuery) && !useMultiHop
            ? elapsedMillis(rerankStartedAt)
            : 0L;
        var filteredChunks = QueryMetadataFilterSupport.filterChunks(resolvedQuery, rerankedChunks);
        var reusableMultiHopContext = useMultiHop
            && !retrievedContext.assembledContext().isBlank()
            && sameChunkIds(retrievedContext.matchedChunks(), filteredChunks);
        var finalChunks = QueryBudgeting.limitChunks(
            filteredChunks,
            remainingChunkBudget(
                resolvedQuery,
                retrievedContext,
                reusableMultiHopContext ? retrievedContext.assembledContext() : null
            )
        );
        var recalculatedWithoutReasoningContext = false;
        if (reusableMultiHopContext && !sameChunkIds(filteredChunks, finalChunks)) {
            finalChunks = QueryBudgeting.limitChunks(
                filteredChunks,
                remainingChunkBudget(resolvedQuery, retrievedContext, null)
            );
            recalculatedWithoutReasoningContext = true;
        }
        var finalMultiHopContextReusable = reusableMultiHopContext
            && !recalculatedWithoutReasoningContext
            && sameChunkIds(filteredChunks, finalChunks);
        var finalContext = new QueryContext(
            retrievedContext.matchedEntities(),
            retrievedContext.matchedRelations(),
            finalChunks,
            ""
        );
        var assembleStartedAt = System.nanoTime();
        var assembledContext = finalMultiHopContextReusable
            ? retrievedContext.assembledContext()
            : contextAssembler.assemble(finalContext);
        var assembleMs = elapsedMillis(assembleStartedAt);
        var assembledQueryContext = new QueryContext(
            finalContext.matchedEntities(),
            finalContext.matchedRelations(),
            finalContext.matchedChunks(),
            assembledContext
        );
        log.info(
            "LightRAG query engine stages: mode={}, resolvedMode={}, query={}, keywordMs={}, retrieveMs={}, rerankMs={}, assembleMs={}, useMultiHop={}, rerankEnabled={}, entityCount={}, relationCount={}, chunkCount={}, elapsedMs={}",
            query.mode(),
            resolvedQuery.mode(),
            query.query(),
            keywordMs,
            retrieveMs,
            rerankMs,
            assembleMs,
            useMultiHop,
            rerankEnabled(resolvedQuery) && !useMultiHop,
            finalContext.matchedEntities().size(),
            finalContext.matchedRelations().size(),
            finalContext.matchedChunks().size(),
            elapsedMillis(startedAt)
        );
        var references = QueryReferences.fromChunks(assembledQueryContext.matchedChunks(), resolvedQuery.includeReferences());
        var chatRequest = new ChatModel.ChatRequest(
            buildSystemPrompt(resolvedQuery, assembledContext),
            resolvedQuery.query(),
            resolvedQuery.conversationHistory()
        );
        return new QueryExecution(responseModel, resolvedQuery, assembledQueryContext, references, chatRequest);
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

    private StructuredQueryResult bypassStructuredQuery(QueryRequest query) {
        var chatRequest = new ChatModel.ChatRequest(
            "",
            buildBypassUserPrompt(query),
            query.conversationHistory()
        );
        var responseModel = selectChatModel(query);
        var answer = query.onlyNeedContext() && !query.onlyNeedPrompt()
            ? ""
            : query.onlyNeedPrompt()
                ? renderBypassPrompt(chatRequest)
                : responseModel.generate(chatRequest);
        return new StructuredQueryResult(answer, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private String resolveStructuredAnswer(QueryExecution execution) {
        if (execution.resolvedQuery().onlyNeedContext() && !execution.resolvedQuery().onlyNeedPrompt()) {
            return execution.queryContext().assembledContext();
        }
        if (execution.resolvedQuery().onlyNeedPrompt()) {
            return renderStandardPrompt(execution.chatRequest());
        }
        return generateStandardAnswer(execution);
    }

    private String generateStandardAnswer(QueryExecution execution) {
        return pathAwareAnswerSynthesizer.shouldUseTwoStage(execution.resolvedQuery(), execution.chatRequest().systemPrompt())
            ? generateTwoStageAnswer(execution.responseModel(), execution.chatRequest())
            : execution.responseModel().generate(execution.chatRequest());
    }

    private ChatModel selectChatModel(QueryRequest request) {
        return request.modelFunc() != null ? request.modelFunc() : chatModel;
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private String buildSystemPrompt(QueryRequest query, String assembledContext) {
        var prompt = systemPromptTemplate(query.mode()).formatted(
            effectiveResponseType(query.responseType()),
            effectiveUserPrompt(query.userPrompt()),
            assembledContext
        );
        return pathAwareAnswerSynthesizer.injectContext("%s", query, prompt);
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

    private int remainingChunkBudget(QueryRequest request, QueryContext context, String assembledContextOverride) {
        var assembledContext = assembledContextOverride;
        if (assembledContext == null || assembledContext.isBlank()) {
            var nonChunkContext = new QueryContext(
                context.matchedEntities(),
                context.matchedRelations(),
                List.of(),
                ""
            );
            assembledContext = contextAssembler.assemble(nonChunkContext);
        }
        var systemPromptTokens = QueryBudgeting.approximateTokenCount(buildSystemPrompt(request, assembledContext));
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

    private static boolean sameChunkIds(List<ScoredChunk> left, List<ScoredChunk> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).chunkId().equals(right.get(i).chunkId())) {
                return false;
            }
        }
        return true;
    }

    private static StructuredQueryEntity toStructuredEntity(ScoredEntity entity) {
        return new StructuredQueryEntity(
            entity.entityId(),
            entity.entity().name(),
            entity.entity().type(),
            entity.entity().description(),
            entity.entity().aliases(),
            entity.entity().sourceChunkIds(),
            entity.score()
        );
    }

    private static StructuredQueryRelation toStructuredRelation(ScoredRelation relation) {
        return new StructuredQueryRelation(
            relation.relationId(),
            relation.relation().srcId(),
            relation.relation().tgtId(),
            relation.relation().keywords(),
            relation.relation().description(),
            relation.relation().weight(),
            relation.relation().sourceChunkIds(),
            relation.relation().filePath(),
            relation.score()
        );
    }

    private static StructuredQueryChunk toStructuredChunk(ScoredChunk chunk) {
        return new StructuredQueryChunk(
            chunk.chunkId(),
            chunk.chunk().documentId(),
            chunk.chunk().text(),
            chunk.chunk().tokenCount(),
            chunk.chunk().order(),
            chunk.chunk().metadata(),
            chunk.score()
        );
    }

    private record QueryExecution(
        ChatModel responseModel,
        QueryRequest resolvedQuery,
        QueryContext queryContext,
        QueryReferences.Result references,
        ChatModel.ChatRequest chatRequest
    ) {
    }
}
