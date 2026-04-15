package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.RerankModel;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineTest {
    @Test
    void rerankReordersFinalContextsAndPromptContext() {
        var chatModel = new RecordingChatModel();
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-3", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-1", 0.77d)
            ))
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .maxHop(4)
            .pathTopK(5)
            .multiHopEnabled(false)
            .responseType("Bullet Points")
            .build());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-3", "chunk-2", "chunk-1");
        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Bullet Points.")
            .containsSubsequence("chunk-3", "chunk-2", "chunk-1")
            .contains("- chunk-3 | 0.700 | Gamma chunk")
            .contains("- chunk-2 | 0.800 | Beta chunk")
            .contains("- chunk-1 | 0.900 | Alpha chunk");
        assertThat(chatModel.lastRequest().userPrompt()).isEqualTo("which chunk?");
        assertThat(result.references()).isEmpty();
        assertThat(strategy.lastRequest().maxHop()).isEqualTo(4);
        assertThat(strategy.lastRequest().pathTopK()).isEqualTo(5);
        assertThat(strategy.lastRequest().multiHopEnabled()).isFalse();
    }

    @Test
    void includeReferencesReturnsStructuredReferencesAndEnrichedContexts() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(referenceContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .includeReferences(true)
            .build());

        assertThat(result.references())
            .containsExactly(
                new io.github.lightrag.api.QueryResult.Reference("1", "alpha.txt"),
                new io.github.lightrag.api.QueryResult.Reference("2", "beta.txt")
            );
        assertThat(result.contexts())
            .extracting(context -> context.referenceId())
            .containsExactly("1", "1", "2");
        assertThat(result.contexts())
            .extracting(context -> context.source())
            .containsExactly("alpha.txt", "alpha.txt", "beta.txt");
    }

    @Test
    void includeReferencesAppliesMetadataFilteringBeforeBuildingContextsAndReferences() {
        var chatModel = new RecordingChatModel();
        var strategy = new RecordingQueryStrategy(new QueryContext(
            List.of(),
            List.of(),
            List.of(
                scoredChunk("chunk-1", "Alpha chunk", 0.90d, "doc-a", Map.of("source", "alpha.txt", "score", "90")),
                scoredChunk("chunk-2", "Beta chunk", 0.80d, "doc-b", Map.of("source", "alpha.txt", "score", "70")),
                scoredChunk("chunk-3", "Gamma chunk", 0.70d, "doc-c", Map.of("file_path", "beta.txt", "score", "95"))
            ),
            "stale assembled context"
        ));
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .includeReferences(true)
            .metadataFilters(Map.of("source", List.of("alpha.txt")))
            .metadataConditions(List.of(
                new io.github.lightrag.api.MetadataCondition(
                    "score",
                    io.github.lightrag.api.MetadataOperator.GTE,
                    "80"
                )
            ))
            .build());

        assertThat(strategy.lastRequest().metadataFilters()).containsEntry("source", List.of("alpha.txt"));
        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1");
        assertThat(result.contexts())
            .extracting(context -> context.referenceId())
            .containsExactly("1");
        assertThat(result.references())
            .containsExactly(new io.github.lightrag.api.QueryResult.Reference("1", "alpha.txt"));
        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("Alpha chunk")
            .doesNotContain("Beta chunk")
            .doesNotContain("Gamma chunk");
    }

    @Test
    void movesAdditionalInstructionsIntoSystemPromptAndKeepsRawQueryAsUserPrompt() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .responseType("Bullet Points")
            .userPrompt("Answer in one sentence.")
            .build());

        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Bullet Points.")
            .contains("Alpha chunk")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
        assertThat(chatModel.lastRequest().userPrompt()).isEqualTo("which chunk?");
    }

    @Test
    void usesNaiveSpecificUpstreamPromptTemplateForNaiveMode() {
        var chatModel = new RecordingChatModel();
        var strategies = new EnumMap<QueryMode, QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.NAIVE, new RecordingQueryStrategy(baseContext()));
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategies,
            null
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.NAIVE)
            .chunkTopK(3)
            .responseType("Single Paragraph")
            .build());

        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .doesNotContain("Knowledge Graph Data")
            .contains("The response should be presented in Single Paragraph.");
        assertThat(chatModel.lastRequest().userPrompt()).isEqualTo("which chunk?");
    }

    @Test
    void forwardsConversationHistoryIntoChatRequest() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var history = List.of(
            new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .conversationHistory(history)
            .build());

        assertThat(chatModel.lastRequest().conversationHistory()).containsExactlyElementsOf(history);
    }

    @Test
    void returnsStreamingAnswerAndRetrievalMetadataWhenStreamIsEnabled() {
        var chatModel = new RecordingChatModel().withStreamResponse("Alpha ", "answer");
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(referenceContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .includeReferences(true)
            .stream(true)
            .build());

        assertThat(result.streaming()).isTrue();
        assertThat(result.answer()).isEmpty();
        assertThat(readAll(result.answerStream())).containsExactly("Alpha ", "answer");
        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
        assertThat(result.references())
            .containsExactly(
                new io.github.lightrag.api.QueryResult.Reference("1", "alpha.txt"),
                new io.github.lightrag.api.QueryResult.Reference("2", "beta.txt")
            );
        assertThat(chatModel.streamCallCount()).isEqualTo(1);
        assertThat(chatModel.callCount()).isZero();
        assertThat(chatModel.lastStreamRequest().userPrompt()).isEqualTo("which chunk?");
    }

    @Test
    void usesQueryLevelModelOverrideForStandardGeneration() {
        var defaultModel = new RecordingChatModel("default answer");
        var overrideModel = new RecordingChatModel("override answer");
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .modelFunc(overrideModel)
            .build());

        assertThat(result.answer()).isEqualTo("override answer");
        assertThat(defaultModel.callCount()).isZero();
        assertThat(overrideModel.callCount()).isEqualTo(1);
    }

    @Test
    void usesQueryLevelModelOverrideForKeywordExtractionAndGeneration() {
        var defaultModel = new RecordingChatModel("default answer");
        var overrideModel = new RecordingChatModel("override answer")
            .withKeywordExtractionResponse("""
                {"high_level_keywords":["organization"],"low_level_keywords":["alice"]}
                """);
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .modelFunc(overrideModel)
            .build());

        assertThat(result.answer()).isEqualTo("override answer");
        assertThat(strategy.lastRequest().llKeywords()).containsExactly("alice");
        assertThat(defaultModel.callCount()).isZero();
        assertThat(overrideModel.keywordExtractionCallCount()).isEqualTo(1);
        assertThat(overrideModel.callCount()).isEqualTo(1);
    }

    @Test
    void usesQueryLevelModelOverrideForStreamingGeneration() {
        var defaultModel = new RecordingChatModel().withStreamResponse("default");
        var overrideModel = new RecordingChatModel().withStreamResponse("override ", "stream");
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .stream(true)
            .modelFunc(overrideModel)
            .build());

        assertThat(readAll(result.answerStream())).containsExactly("override ", "stream");
        assertThat(defaultModel.streamCallCount()).isZero();
        assertThat(overrideModel.streamCallCount()).isEqualTo(1);
    }

    @Test
    void streamingResultClosesUnderlyingAnswerStreamWhenClosed() throws Exception {
        var chatModel = new RecordingChatModel().withStreamResponse("Alpha ", "answer");
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        try (var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .stream(true)
            .build())) {
            assertThat(result.streaming()).isTrue();
        }

        assertThat(chatModel.streamCloseCount()).isEqualTo(1);
    }

    @Test
    void shortcutsDoNotInvokeQueryLevelModelOverride() {
        var defaultModel = new RecordingChatModel("default answer");
        var overrideModel = new RecordingChatModel("override answer");
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .onlyNeedPrompt(true)
            .modelFunc(overrideModel)
            .build());

        assertThat(defaultModel.callCount()).isZero();
        assertThat(defaultModel.streamCallCount()).isZero();
        assertThat(overrideModel.callCount()).isZero();
        assertThat(overrideModel.streamCallCount()).isZero();
    }

    @Test
    void returnsAssembledContextWithoutCallingChatModelWhenOnlyNeedContextIsEnabled() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-3", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-1", 0.77d)
            ))
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .stream(true)
            .onlyNeedContext(true)
            .build());

        assertThat(result.answer())
            .contains("Entities:")
            .contains("Relations:")
            .contains("Chunks:")
            .contains("chunk-3")
            .contains("chunk-2")
            .contains("chunk-1");
        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-3", "chunk-2", "chunk-1");
        assertThat(chatModel.callCount()).isZero();
        assertThat(chatModel.streamCallCount()).isZero();
        assertThat(chatModel.lastRequest()).isNull();
        assertThat(chatModel.lastStreamRequest()).isNull();
    }

    @Test
    void returnsCompletePromptPayloadWithoutCallingChatModelWhenOnlyNeedPromptIsEnabled() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-3", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-1", 0.77d)
            ))
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .responseType("Single Paragraph")
            .userPrompt("Answer in one sentence.")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            ))
            .stream(true)
            .onlyNeedPrompt(true)
            .build());

        assertThat(result.answer())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("Knowledge Graph Data")
            .contains("Document Chunks")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Single Paragraph.")
            .contains("---User Query---")
            .contains("which chunk?")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.")
            .doesNotContain("Earlier question")
            .doesNotContain("Earlier answer");
        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-3", "chunk-2", "chunk-1");
        assertThat(chatModel.callCount()).isZero();
        assertThat(chatModel.streamCallCount()).isZero();
        assertThat(chatModel.lastRequest()).isNull();
        assertThat(chatModel.lastStreamRequest()).isNull();
    }

    @Test
    void onlyNeedPromptTakesPrecedenceOverOnlyNeedContext() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .onlyNeedContext(true)
            .onlyNeedPrompt(true)
            .build());

        assertThat(result.answer())
            .contains("---User Query---")
            .contains("which chunk?");
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    void keepsHistorySeparateAndUsesNaForBlankUserPrompt() {
        var chatModel = new RecordingChatModel();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .responseType("Single Paragraph")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            ))
            .build());

        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("---Role---")
            .contains("---Goal---")
            .contains("---Instructions---")
            .contains("---Context---")
            .contains("DO NOT invent")
            .contains("same language as the user query")
            .contains("### References")
            .contains("The response should be presented in Single Paragraph.")
            .contains("Additional Instructions:")
            .contains("n/a")
            .doesNotContain("Earlier question")
            .doesNotContain("Earlier answer");
        assertThat(chatModel.lastRequest().userPrompt())
            .isEqualTo("which chunk?")
            .doesNotContain("Earlier question")
            .doesNotContain("Earlier answer");
    }

    @Test
    void expandsChunkTopKWhenRerankIsEnabledAndModelIsConfigured() {
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-1", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-3", 0.77d)
            ))
        );

        engine.query(baseRequest());

        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(6);
    }

    @Test
    void usesConfiguredRerankCandidateMultiplier() {
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-1", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-3", 0.77d)
            )),
            true,
            4
        );

        engine.query(baseRequest());

        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(12);
    }

    @Test
    void preservesPromptCustomizationAndGraphBudgetsWhenRerankExpandsChunkRequest() {
        var history = List.of(
            new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        );
        var metadataConditions = List.of(
            new io.github.lightrag.api.MetadataCondition("score", io.github.lightrag.api.MetadataOperator.GTE, "80")
        );
        var overrideModel = new RecordingChatModel("override answer");
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-1", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-3", 0.77d)
            ))
        );

        engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .responseType("Bullet Points")
            .userPrompt("Answer in one sentence.")
            .maxEntityTokens(111)
            .maxRelationTokens(222)
            .maxTotalTokens(333)
            .includeReferences(true)
            .stream(true)
            .modelFunc(overrideModel)
            .hlKeywords(List.of("high level"))
            .llKeywords(List.of("low level"))
            .conversationHistory(history)
            .metadataFilters(Map.of("source", List.of("doc-a")))
            .metadataConditions(metadataConditions)
            .build());

        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(6);
        assertThat(strategy.lastRequest().responseType()).isEqualTo("Bullet Points");
        assertThat(strategy.lastRequest().userPrompt()).isEqualTo("Answer in one sentence.");
        assertThat(strategy.lastRequest().maxEntityTokens()).isEqualTo(111);
        assertThat(strategy.lastRequest().maxRelationTokens()).isEqualTo(222);
        assertThat(strategy.lastRequest().maxTotalTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(strategy.lastRequest().includeReferences()).isTrue();
        assertThat(strategy.lastRequest().stream()).isTrue();
        assertThat(strategy.lastRequest().modelFunc()).isSameAs(overrideModel);
        assertThat(strategy.lastRequest().hlKeywords()).containsExactly("high level");
        assertThat(strategy.lastRequest().llKeywords()).containsExactly("low level");
        assertThat(strategy.lastRequest().conversationHistory()).containsExactlyElementsOf(history);
        assertThat(strategy.lastRequest().metadataFilters()).containsEntry("source", List.of("doc-a"));
        assertThat(strategy.lastRequest().metadataConditions()).containsExactlyElementsOf(metadataConditions);
    }

    @Test
    void preservesRetrievalOrderWhenNoRerankModelIsConfigured() {
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(baseRequest());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(3);
    }

    @Test
    void trimsFinalChunksToRemainingMaxTotalTokensAfterRetrieval() {
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .maxTotalTokens(1)
            .build());

        assertThat(result.contexts()).isEmpty();
    }

    @Test
    void rerankStillAppliesOriginalMaxTotalTokensAfterExpandedRetrieval() {
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-3", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-1", 0.77d)
            ))
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .chunkTopK(3)
            .maxTotalTokens(1)
            .build());

        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().maxTotalTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(result.contexts()).isEmpty();
    }

    @Test
    void bypassesRerankWhenQueryRequestDisablesIt() {
        var strategy = new RecordingQueryStrategy(baseContext());
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(strategy),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-3", 0.99d),
                new RerankModel.RerankResult("chunk-2", 0.88d),
                new RerankModel.RerankResult("chunk-1", 0.77d)
            ))
        );

        var result = engine.query(QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .enableRerank(false)
            .build());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(3);
    }

    @Test
    void appendsOmittedCandidatesInOriginalOrderAfterRerankResults() {
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            new StubRerankModel(List.of(
                new RerankModel.RerankResult("chunk-2", 0.99d),
                new RerankModel.RerankResult("missing", 0.88d)
            ))
        );

        var result = engine.query(baseRequest());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-2", "chunk-1", "chunk-3");
    }

    @Test
    void propagatesRerankFailures() {
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            request -> {
                throw new IllegalStateException("rerank failure");
            }
        );

        assertThatThrownBy(() -> engine.query(baseRequest()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("rerank failure");
    }

    @Test
    void bypassModeSkipsRetrievalAndCallsChatModelDirectly() {
        var chatModel = new RecordingChatModel("bypass answer");
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .userPrompt("Answer in one sentence.")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            ))
            .build());

        assertThat(result.answer()).isEqualTo("bypass answer");
        assertThat(result.contexts()).isEmpty();
        assertThat(result.references()).isEmpty();
        assertThat(strategy.callCount()).isZero();
        assertThat(chatModel.callCount()).isEqualTo(1);
        assertThat(chatModel.lastRequest().systemPrompt()).isEmpty();
        assertThat(chatModel.lastRequest().userPrompt())
            .contains("talk directly to the model")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
        assertThat(chatModel.lastRequest().conversationHistory())
            .containsExactly(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
                new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
            );
    }

    @Test
    void bypassModeReturnsEmptyAnswerWithoutCallingChatModelWhenOnlyNeedContextIsEnabled() {
        var chatModel = new RecordingChatModel();
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .onlyNeedContext(true)
            .build());

        assertThat(result.answer()).isEmpty();
        assertThat(result.contexts()).isEmpty();
        assertThat(strategy.callCount()).isZero();
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    void bypassStreamsDirectlyFromChatModelWhenRequested() {
        var chatModel = new RecordingChatModel().withStreamResponse("Bypass ", "answer");
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .userPrompt("Answer in one sentence.")
            .stream(true)
            .build());

        assertThat(result.streaming()).isTrue();
        assertThat(result.answer()).isEmpty();
        assertThat(readAll(result.answerStream())).containsExactly("Bypass ", "answer");
        assertThat(result.contexts()).isEmpty();
        assertThat(result.references()).isEmpty();
        assertThat(strategy.callCount()).isZero();
        assertThat(chatModel.callCount()).isZero();
        assertThat(chatModel.streamCallCount()).isEqualTo(1);
        assertThat(chatModel.lastStreamRequest().systemPrompt()).isEmpty();
        assertThat(chatModel.lastStreamRequest().userPrompt())
            .contains("talk directly to the model")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
    }

    @Test
    void bypassUsesQueryLevelModelOverride() {
        var defaultModel = new RecordingChatModel("default answer");
        var overrideModel = new RecordingChatModel("override bypass");
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .modelFunc(overrideModel)
            .build());

        assertThat(result.answer()).isEqualTo("override bypass");
        assertThat(defaultModel.callCount()).isZero();
        assertThat(overrideModel.callCount()).isEqualTo(1);
        assertThat(strategy.callCount()).isZero();
    }

    @Test
    void bypassStreamingUsesQueryLevelModelOverride() {
        var defaultModel = new RecordingChatModel().withStreamResponse("default");
        var overrideModel = new RecordingChatModel().withStreamResponse("override ", "bypass");
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            defaultModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .stream(true)
            .modelFunc(overrideModel)
            .build());

        assertThat(readAll(result.answerStream())).containsExactly("override ", "bypass");
        assertThat(defaultModel.streamCallCount()).isZero();
        assertThat(overrideModel.streamCallCount()).isEqualTo(1);
        assertThat(strategy.callCount()).isZero();
    }

    @Test
    void bypassModeReturnsPromptPayloadWithoutCallingChatModelWhenOnlyNeedPromptIsEnabled() {
        var chatModel = new RecordingChatModel();
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .userPrompt("Answer in one sentence.")
            .conversationHistory(List.of(
                new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question")
            ))
            .onlyNeedPrompt(true)
            .build());

        assertThat(result.answer())
            .contains("System Prompt:")
            .contains("History:")
            .contains("Earlier question")
            .contains("User Prompt:")
            .contains("talk directly to the model")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
        assertThat(result.contexts()).isEmpty();
        assertThat(strategy.callCount()).isZero();
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    void bypassModeOnlyNeedContextStillWinsWhenBothShortcutFlagsAreEnabled() {
        var chatModel = new RecordingChatModel();
        var strategy = new FailingQueryStrategy();
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(strategy),
            null
        );

        var result = engine.query(QueryRequest.builder()
            .query("talk directly to the model")
            .mode(QueryMode.BYPASS)
            .onlyNeedContext(true)
            .onlyNeedPrompt(true)
            .build());

        assertThat(result.answer()).isEmpty();
        assertThat(result.contexts()).isEmpty();
        assertThat(strategy.callCount()).isZero();
        assertThat(chatModel.callCount()).isZero();
    }

    @Test
    void routesMultiHopQuestionsToDedicatedStrategyAndPreservesReasoningContext() {
        var chatModel = new RecordingChatModel();
        var defaultStrategy = new RecordingQueryStrategy(baseContext());
        var multiHopStrategy = new RecordingQueryStrategy(new QueryContext(
            List.of(),
            List.of(),
            List.of(scoredChunk("chunk-2", "GraphStore 服务由知识图谱组维护。", 0.95d)),
            "Reasoning Path 1\nHop 1\nHop 2"
        ));
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(defaultStrategy),
            null,
            false,
            2,
            new RuleBasedQueryIntentClassifier(),
            multiHopStrategy,
            new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
        );

        var result = engine.query(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.LOCAL)
            .build());

        assertThat(defaultStrategy.lastRequest()).isNull();
        assertThat(multiHopStrategy.lastRequest()).isNotNull();
        assertThat(result.answer()).isEqualTo("answer");
        assertThat(chatModel.lastRequest().systemPrompt())
            .contains("Reasoning Path 1")
            .contains("explain the answer hop by hop")
            .contains("do not have enough information");
    }

    @Test
    void multiHopOnlyNeedContextFallsBackWhenMetadataFilteringChangesReturnedChunks() {
        var chatModel = new RecordingChatModel();
        var defaultStrategy = new RecordingQueryStrategy(baseContext());
        var multiHopStrategy = new RecordingQueryStrategy(new QueryContext(
            List.of(),
            List.of(),
            List.of(
                scoredChunk("chunk-1", "Allowed evidence", 0.95d, "doc-a", Map.of("region", "shanghai", "source", "alpha.txt")),
                scoredChunk("chunk-2", "Filtered evidence", 0.90d, "doc-b", Map.of("region", "beijing", "source", "beta.txt"))
            ),
            """
            Reasoning Path 1
            Evidence [chunk-1]: Allowed evidence
            Evidence [chunk-2]: Filtered evidence
            """
        ));
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(defaultStrategy),
            null,
            false,
            2,
            new RuleBasedQueryIntentClassifier(),
            multiHopStrategy,
            new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
        );

        var result = engine.query(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.LOCAL)
            .onlyNeedContext(true)
            .includeReferences(true)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(result.answer())
            .contains("Allowed evidence")
            .doesNotContain("Filtered evidence")
            .doesNotContain("Reasoning Path 1");
        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1");
        assertThat(result.references())
            .containsExactly(new io.github.lightrag.api.QueryResult.Reference("1", "alpha.txt"));
    }

    @Test
    void recalculatesMultiHopChunkBudgetWithoutReasoningContextWhenTrimmedChunksPreventReuse() {
        var chatModel = new RecordingChatModel();
        var defaultStrategy = new RecordingQueryStrategy(baseContext());
        var reasoningContext = ("Reasoning Path 1 " + "hop ".repeat(80)).trim();
        var retrievedContext = new QueryContext(
            List.of(),
            List.of(),
            List.of(new ScoredChunk("chunk-1", new Chunk("chunk-1", "doc-1", "Alpha chunk", 20, 0, Map.of()), 0.9d)),
            reasoningContext
        );
        var reasoningMultiHopStrategy = new RecordingQueryStrategy(retrievedContext);
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(defaultStrategy),
            null,
            false,
            2,
            new RuleBasedQueryIntentClassifier(),
            reasoningMultiHopStrategy,
            new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
        );

        var probeRequest = QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.LOCAL)
            .maxTotalTokens(10_000)
            .build();
        var reasoningBudget = invokeRemainingChunkBudget(engine, probeRequest, retrievedContext, reasoningContext);
        var fallbackBudget = invokeRemainingChunkBudget(engine, probeRequest, retrievedContext, null);
        assertThat(fallbackBudget).isGreaterThan(reasoningBudget);
        var fallbackOverhead = probeRequest.maxTotalTokens() - fallbackBudget;
        var targetMaxTotalTokens = fallbackOverhead + retrievedContext.matchedChunks().get(0).chunk().tokenCount();

        var result = engine.query(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.LOCAL)
            .maxTotalTokens(targetMaxTotalTokens)
            .build());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1");
        assertThat(chatModel.lastRequest().systemPrompt()).doesNotContain(reasoningContext);
        assertThat(chatModel.lastRequest().systemPrompt()).contains("Alpha chunk");
    }

    private static int invokeRemainingChunkBudget(
        QueryEngine engine,
        QueryRequest request,
        QueryContext context,
        String assembledContextOverride
    ) {
        try {
            var method = QueryEngine.class.getDeclaredMethod(
                "remainingChunkBudget",
                QueryRequest.class,
                QueryContext.class,
                String.class
            );
            method.setAccessible(true);
            return (int) method.invoke(engine, request, context, assembledContextOverride);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Unable to inspect remainingChunkBudget", exception);
        }
    }

    @Test
    void doesNotRouteNaiveModeThroughMultiHopStrategyEvenForIndirectQuestion() {
        var chatModel = new RecordingChatModel();
        var defaultStrategy = new RecordingQueryStrategy(baseContext());
        var multiHopStrategy = new RecordingQueryStrategy(new QueryContext(List.of(), List.of(), List.of(), "Reasoning Path 1"));
        var strategies = new EnumMap<QueryMode, QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.NAIVE, defaultStrategy);
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategies,
            null,
            true,
            2,
            new RuleBasedQueryIntentClassifier(),
            multiHopStrategy,
            new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
        );

        engine.query(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.NAIVE)
            .build());

        assertThat(defaultStrategy.lastRequest()).isNotNull();
        assertThat(multiHopStrategy.lastRequest()).isNull();
    }

    @Test
    void usesTwoStageSynthesisForNonStreamingMultiHopAnswers() {
        var chatModel = new SequencedChatModel(
            "Step 1: Atlas depends on GraphStore.\nStep 2: GraphStore is owned by KnowledgeGraphTeam.",
            "Atlas 通过 GraphStore 影响知识图谱组。"
        );
        var defaultStrategy = new RecordingQueryStrategy(baseContext());
        var multiHopStrategy = new RecordingQueryStrategy(new QueryContext(
            List.of(),
            List.of(),
            List.of(scoredChunk("chunk-2", "GraphStore 服务由知识图谱组维护。", 0.95d)),
            "Reasoning Path 1\nHop 1: Atlas --depends_on--> GraphStore\nHop 2: GraphStore --owned_by--> KnowledgeGraphTeam"
        ));
        var engine = new QueryEngine(
            chatModel,
            new ContextAssembler(),
            strategiesReturning(defaultStrategy),
            null,
            false,
            2,
            new RuleBasedQueryIntentClassifier(),
            multiHopStrategy,
            new io.github.lightrag.synthesis.PathAwareAnswerSynthesizer()
        );

        var result = engine.query(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .mode(QueryMode.LOCAL)
            .build());

        assertThat(result.answer()).isEqualTo("Atlas 通过 GraphStore 影响知识图谱组。");
        assertThat(chatModel.callCount()).isEqualTo(2);
        assertThat(chatModel.requests()).hasSize(2);
        assertThat(chatModel.requests().get(0).systemPrompt())
            .contains("Reasoning Draft Instructions")
            .contains("Do not write the final answer");
        assertThat(chatModel.requests().get(1).systemPrompt())
            .contains("Validated Reasoning Draft")
            .contains("Step 1: Atlas depends on GraphStore.")
            .contains("Step 2: GraphStore is owned by KnowledgeGraphTeam.");
    }

    private static QueryRequest baseRequest() {
        return QueryRequest.builder()
            .query("which chunk?")
            .mode(QueryMode.LOCAL)
            .topK(3)
            .chunkTopK(3)
            .build();
    }

    private static QueryContext baseContext() {
        var chunks = List.of(
            scoredChunk("chunk-1", "Alpha chunk", 0.90d),
            scoredChunk("chunk-2", "Beta chunk", 0.80d),
            scoredChunk("chunk-3", "Gamma chunk", 0.70d)
        );
        return new QueryContext(List.of(), List.of(), chunks, "stale assembled context");
    }

    private static QueryContext referenceContext() {
        var chunks = List.of(
            scoredChunk("chunk-1", "Alpha chunk", 0.90d, "doc-a", Map.of("source", "alpha.txt")),
            scoredChunk("chunk-2", "Beta chunk", 0.80d, "doc-b", Map.of("source", "alpha.txt")),
            scoredChunk("chunk-3", "Gamma chunk", 0.70d, "doc-c", Map.of("file_path", "beta.txt"))
        );
        return new QueryContext(List.of(), List.of(), chunks, "stale assembled context");
    }

    private static EnumMap<QueryMode, QueryStrategy> strategiesReturning(QueryStrategy strategy) {
        var strategies = new EnumMap<QueryMode, QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.LOCAL, strategy);
        return strategies;
    }

    private static EnumMap<QueryMode, QueryStrategy> strategiesReturning(QueryContext context) {
        return strategiesReturning(new RecordingQueryStrategy(context));
    }

    private static final class FailingQueryStrategy implements QueryStrategy {
        private int callCount;

        @Override
        public QueryContext retrieve(QueryRequest request) {
            callCount++;
            throw new AssertionError("retrieve should not be called");
        }

        int callCount() {
            return callCount;
        }
    }

    private static ScoredChunk scoredChunk(String chunkId, String text, double score) {
        return scoredChunk(chunkId, text, score, "doc-1", Map.of());
    }

    private static ScoredChunk scoredChunk(
        String chunkId,
        String text,
        double score,
        String documentId,
        Map<String, String> metadata
    ) {
        return new ScoredChunk(chunkId, new Chunk(chunkId, documentId, text, 3, 0, metadata), score);
    }

    private record StubRerankModel(List<RerankModel.RerankResult> results) implements RerankModel {
        @Override
        public List<RerankResult> rerank(RerankRequest request) {
            return results;
        }
    }

    private static final class RecordingQueryStrategy implements QueryStrategy {
        private final QueryContext context;
        private QueryRequest lastRequest;

        private RecordingQueryStrategy(QueryContext context) {
            this.context = context;
        }

        @Override
        public QueryContext retrieve(QueryRequest request) {
            lastRequest = request;
            return context;
        }

        QueryRequest lastRequest() {
            return lastRequest;
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final String response;
        private List<String> streamResponse = List.of("answer");
        private ChatRequest lastRequest;
        private ChatRequest lastStreamRequest;

        private int callCount;
        private int streamCallCount;
        private int streamCloseCount;
        private String keywordExtractionResponse;
        private int keywordExtractionCallCount;

        private RecordingChatModel() {
            this("answer");
        }

        private RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public String generate(ChatRequest request) {
            if (request.userPrompt().contains("high_level_keywords")
                && request.userPrompt().contains("low_level_keywords")) {
                keywordExtractionCallCount++;
                return keywordExtractionResponse == null ? "{\"high_level_keywords\":[],\"low_level_keywords\":[]}" : keywordExtractionResponse;
            }
            callCount++;
            lastRequest = request;
            return response;
        }

        @Override
        public io.github.lightrag.model.CloseableIterator<String> stream(ChatRequest request) {
            streamCallCount++;
            lastStreamRequest = request;
            return new io.github.lightrag.model.CloseableIterator<>() {
                private int index;
                private boolean closed;

                @Override
                public boolean hasNext() {
                    return !closed && index < streamResponse.size();
                }

                @Override
                public String next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return streamResponse.get(index++);
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        streamCloseCount++;
                    }
                }
            };
        }

        RecordingChatModel withStreamResponse(String... fragments) {
            streamResponse = List.of(fragments);
            return this;
        }

        RecordingChatModel withKeywordExtractionResponse(String keywordExtractionResponse) {
            this.keywordExtractionResponse = keywordExtractionResponse;
            return this;
        }

        ChatRequest lastRequest() {
            return lastRequest;
        }

        ChatRequest lastStreamRequest() {
            return lastStreamRequest;
        }

        int callCount() {
            return callCount;
        }

        int streamCallCount() {
            return streamCallCount;
        }

        int streamCloseCount() {
            return streamCloseCount;
        }

        int keywordExtractionCallCount() {
            return keywordExtractionCallCount;
        }
    }

    private static final class SequencedChatModel implements ChatModel {
        private final List<String> responses;
        private final java.util.ArrayList<ChatRequest> requests = new java.util.ArrayList<>();
        private int index;

        private SequencedChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            var responseIndex = Math.min(index, responses.size() - 1);
            index++;
            return responses.get(responseIndex);
        }

        int callCount() {
            return requests.size();
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }
    }

    private static List<String> readAll(io.github.lightrag.model.CloseableIterator<String> iterator) {
        try (iterator) {
            var values = new java.util.ArrayList<String>();
            while (iterator.hasNext()) {
                values.add(iterator.next());
            }
            return values;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }
}
