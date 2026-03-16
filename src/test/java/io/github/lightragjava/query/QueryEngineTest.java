package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryEngineTest {
    @Test
    void rerankReordersFinalContextsAndPromptContext() {
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
        assertThat(chatModel.lastRequest()).isNull();
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
        assertThat(chatModel.lastRequest()).isNull();
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
    void preservesPromptCustomizationAndGraphBudgetsWhenRerankExpandsChunkRequest() {
        var history = List.of(
            new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"),
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        );
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
            .hlKeywords(List.of("high level"))
            .llKeywords(List.of("low level"))
            .conversationHistory(history)
            .build());

        assertThat(strategy.lastRequest()).isNotNull();
        assertThat(strategy.lastRequest().chunkTopK()).isEqualTo(6);
        assertThat(strategy.lastRequest().responseType()).isEqualTo("Bullet Points");
        assertThat(strategy.lastRequest().userPrompt()).isEqualTo("Answer in one sentence.");
        assertThat(strategy.lastRequest().maxEntityTokens()).isEqualTo(111);
        assertThat(strategy.lastRequest().maxRelationTokens()).isEqualTo(222);
        assertThat(strategy.lastRequest().maxTotalTokens()).isEqualTo(Integer.MAX_VALUE);
        assertThat(strategy.lastRequest().hlKeywords()).containsExactly("high level");
        assertThat(strategy.lastRequest().llKeywords()).containsExactly("low level");
        assertThat(strategy.lastRequest().conversationHistory()).containsExactlyElementsOf(history);
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
        return new ScoredChunk(chunkId, new Chunk(chunkId, "doc-1", text, 3, 0, java.util.Map.of()), score);
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
        private ChatRequest lastRequest;

        private int callCount;

        private RecordingChatModel() {
            this("answer");
        }

        private RecordingChatModel(String response) {
            this.response = response;
        }

        @Override
        public String generate(ChatRequest request) {
            callCount++;
            lastRequest = request;
            return response;
        }

        ChatRequest lastRequest() {
            return lastRequest;
        }

        int callCount() {
            return callCount;
        }
    }
}
