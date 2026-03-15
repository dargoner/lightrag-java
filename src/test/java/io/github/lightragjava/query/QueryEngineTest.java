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
            .containsSubsequence("chunk-3", "chunk-2", "chunk-1");
    }

    @Test
    void preservesRetrievalOrderWhenNoRerankModelIsConfigured() {
        var engine = new QueryEngine(
            new RecordingChatModel(),
            new ContextAssembler(),
            strategiesReturning(baseContext()),
            null
        );

        var result = engine.query(baseRequest());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
    }

    @Test
    void bypassesRerankWhenQueryRequestDisablesIt() {
        var engine = new QueryEngine(
            new RecordingChatModel(),
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
            .enableRerank(false)
            .build());

        assertThat(result.contexts())
            .extracting(context -> context.sourceId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
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

    private static EnumMap<QueryMode, QueryStrategy> strategiesReturning(QueryContext context) {
        var strategies = new EnumMap<QueryMode, QueryStrategy>(QueryMode.class);
        strategies.put(QueryMode.LOCAL, request -> context);
        return strategies;
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
