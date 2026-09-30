package io.github.lightrag.indexing;

import io.github.lightrag.api.CancellationCheckpoint;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMaterializationPipelineConcurrencyTest {
    @Test
    void defaultsToSequentialExtractionWhenParallelismIsNotConfigured() throws Exception {
        var chatModel = new RecordingConcurrentChatModel(0);
        var pipeline = new GraphMaterializationPipeline(
            chatModel,
            new FakeEmbeddingModel(),
            InMemoryStorageProvider.create(),
            ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop()
        );

        var extractions = invokeRefineExtractions(pipeline, List.of(
            chunk("doc-1:0", "zero"),
            chunk("doc-1:1", "one"),
            chunk("doc-1:2", "two")
        ));

        assertThat(chatModel.maxConcurrentCalls()).isEqualTo(1);
        assertThat(extractions).extracting(GraphAssembler.ChunkExtraction::chunkId)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    }

    @Test
    void normalizesNonPositiveParallelismToOne() throws Exception {
        var chatModel = new RecordingConcurrentChatModel(0);
        var pipeline = new GraphMaterializationPipeline(
            chatModel,
            new FakeEmbeddingModel(),
            InMemoryStorageProvider.create(),
            ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop(),
            0,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            List.of(),
            List.of(),
            CancellationCheckpoint.NONE
        );

        var extractions = invokeRefineExtractions(pipeline, List.of(
            chunk("doc-1:0", "zero"),
            chunk("doc-1:1", "one"),
            chunk("doc-1:2", "two")
        ));

        assertThat(chatModel.maxConcurrentCalls()).isEqualTo(1);
        assertThat(extractions).extracting(GraphAssembler.ChunkExtraction::chunkId)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    }

    @SuppressWarnings("unchecked")
    private static List<GraphAssembler.ChunkExtraction> invokeRefineExtractions(
        GraphMaterializationPipeline pipeline,
        List<Chunk> chunks
    ) throws Exception {
        var method = GraphMaterializationPipeline.class.getDeclaredMethod("refineExtractions", List.class);
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
        private final CountDownLatch barrier;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();

        private RecordingConcurrentChatModel(int barrierSize) {
            this.barrier = barrierSize > 1 ? new CountDownLatch(barrierSize) : null;
        }

        @Override
        public String generate(ChatRequest request) {
            var current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                if (barrier != null) {
                    barrier.countDown();
                    await(barrier);
                }
                return successResponse(chunkId(request));
            } finally {
                inFlight.decrementAndGet();
            }
        }

        int maxConcurrentCalls() {
            return maxInFlight.get();
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
}
