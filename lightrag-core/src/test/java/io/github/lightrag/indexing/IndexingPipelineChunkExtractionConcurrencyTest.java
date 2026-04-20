package io.github.lightrag.indexing;

import io.github.lightrag.api.LightRag;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingPipelineChunkExtractionConcurrencyTest {
    @Test
    void preservesChunkOrderWhenExtractionRunsConcurrently() throws Exception {
        var chatModel = new RecordingConcurrentChatModel();
        var pipeline = new IndexingPipeline(
            chatModel,
            chatModel,
            new FakeEmbeddingModel(),
            InMemoryStorageProvider.create(),
            null,
            null,
            null,
            Integer.MAX_VALUE,
            1,
            3,
            0,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            false,
            0.80d,
            ExtractionRefinementOptions.disabled(),
            IndexingProgressListener.noop()
        );

        var extractions = invokeRefineExtractions(pipeline, List.of(
            chunk("doc-1:0", "zero"),
            chunk("doc-1:1", "one"),
            chunk("doc-1:2", "two")
        ));

        assertThat(chatModel.maxConcurrentCalls()).isGreaterThanOrEqualTo(2);
        assertThat(extractions).extracting(GraphAssembler.ChunkExtraction::chunkId)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    }

    @Test
    void failsWholeBatchWhenAnyConcurrentExtractionFails() {
        var chatModel = new FailingConcurrentChatModel("doc-1:1");
        var pipeline = new IndexingPipeline(
            chatModel,
            chatModel,
            new FakeEmbeddingModel(),
            InMemoryStorageProvider.create(),
            null,
            null,
            null,
            Integer.MAX_VALUE,
            1,
            3,
            0,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            false,
            0.80d,
            ExtractionRefinementOptions.disabled(),
            IndexingProgressListener.noop()
        );

        assertThatThrownBy(() -> invokeRefineExtractions(pipeline, List.of(
            chunk("doc-1:0", "zero"),
            chunk("doc-1:1", "one"),
            chunk("doc-1:2", "two")
        )))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("boom");
    }

    @Test
    void propagatesChunkExtractParallelismThroughLightRagBuilder() {
        var chatModel = new RecordingConcurrentChatModel();
        try (var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .chunker(new ThreeChunkChunker())
            .chunkExtractParallelism(3)
            .entityExtractMaxGleaning(0)
            .build()) {

            rag.ingest("default", List.of(new Document("doc-1", "title", "ignored", Map.of())));
        }

        assertThat(chatModel.maxConcurrentCalls()).isGreaterThanOrEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static List<GraphAssembler.ChunkExtraction> invokeRefineExtractions(
        IndexingPipeline pipeline,
        List<Chunk> chunks
    ) throws Exception {
        var method = IndexingPipeline.class.getDeclaredMethod("refineExtractions", List.class);
        method.setAccessible(true);
        try {
            return (List<GraphAssembler.ChunkExtraction>) method.invoke(pipeline, chunks);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception checked) {
                throw checked;
            }
            if (exception.getCause() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(exception.getCause());
        }
    }

    private static Chunk chunk(String chunkId, String text) {
        return new Chunk(chunkId, "doc-1", text, text.length(), Integer.parseInt(chunkId.substring(chunkId.length() - 1)), Map.of());
    }

    private static String chunkId(ChatModel.ChatRequest request) {
        var prefix = "Chunk ID: ";
        for (var line : request.userPrompt().lines().toList()) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        throw new IllegalStateException("chunk id missing from prompt");
    }

    private static final class RecordingConcurrentChatModel implements ChatModel {
        private final CountDownLatch twoEntered = new CountDownLatch(2);
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        @Override
        public String generate(ChatRequest request) {
            var current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                twoEntered.countDown();
                await(twoEntered);
                sleepFor(chunkId(request));
                return successResponse(chunkId(request));
            } finally {
                inFlight.decrementAndGet();
            }
        }

        int maxConcurrentCalls() {
            return maxInFlight.get();
        }

        private static void sleepFor(String chunkId) {
            try {
                switch (chunkId) {
                    case "doc-1:0" -> Thread.sleep(120L);
                    case "doc-1:1" -> Thread.sleep(40L);
                    default -> Thread.sleep(0L);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("sleep interrupted", exception);
            }
        }
    }

    private static final class FailingConcurrentChatModel implements ChatModel {
        private final String failingChunkId;
        private final CountDownLatch twoEntered = new CountDownLatch(2);

        private FailingConcurrentChatModel(String failingChunkId) {
            this.failingChunkId = failingChunkId;
        }

        @Override
        public String generate(ChatRequest request) {
            var chunkId = chunkId(request);
            twoEntered.countDown();
            await(twoEntered);
            if (failingChunkId.equals(chunkId)) {
                throw new RuntimeException("boom for " + chunkId);
            }
            return successResponse(chunkId);
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            var vectors = new ArrayList<List<Double>>(texts.size());
            for (var text : texts) {
                vectors.add(List.of((double) text.length(), 1.0d));
            }
            return List.copyOf(vectors);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("timed out waiting for concurrent extraction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("await interrupted", exception);
        }
    }

    private static String successResponse(String chunkId) {
        return """
            {"entities":[{"name":"%s","type":"Chunk","description":"%s","aliases":[]}],"relations":[]}
            """.formatted(chunkId, chunkId);
    }

    private static final class ThreeChunkChunker implements Chunker {
        @Override
        public List<Chunk> chunk(Document document) {
            return List.of(
                IndexingPipelineChunkExtractionConcurrencyTest.chunk(document.id() + ":0", "zero"),
                IndexingPipelineChunkExtractionConcurrencyTest.chunk(document.id() + ":1", "one"),
                IndexingPipelineChunkExtractionConcurrencyTest.chunk(document.id() + ":2", "two")
            );
        }
    }
}
