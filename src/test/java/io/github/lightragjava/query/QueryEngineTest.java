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

        var result = engine.query(baseRequest());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-3", "chunk-2", "chunk-1");
        assertThat(chatModel.lastRequest().userPrompt())
            .containsSubsequence("chunk-3", "chunk-2", "chunk-1")
            .contains("- chunk-3 | 0.700 | Gamma chunk")
            .contains("- chunk-2 | 0.800 | Beta chunk")
            .contains("- chunk-1 | 0.900 | Alpha chunk");
    }

    @Test
    void appendsUserPromptToFinalCurrentTurnPrompt() {
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
            .userPrompt("Answer in one sentence.")
            .build());

        assertThat(chatModel.lastRequest().userPrompt())
            .contains("Question:")
            .contains("which chunk?")
            .contains("Additional Instructions:")
            .contains("Answer in one sentence.");
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
        private ChatRequest lastRequest;

        @Override
        public String generate(ChatRequest request) {
            lastRequest = request;
            return "answer";
        }

        ChatRequest lastRequest() {
            return lastRequest;
        }
    }
}
