package io.github.lightrag.indexing;

import io.github.lightrag.api.CancellationCheckpoint;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Chunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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

    @Test
    void runsChunkExtractionInParallelWhenParallelismIsGreaterThanOne() throws Exception {
        var chatModel = new RecordingConcurrentChatModel(3);
        var pipeline = newPipeline(InMemoryStorageProvider.create(), chatModel, 3, CancellationCheckpoint.NONE);

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
    void failsWholeBatchAndCancelsPendingWhenOneParallelExtractionFails() throws Exception {
        var chatModel = new FailingConcurrentChatModel(3, "doc-1:1", "doc-1:2");
        var pipeline = newPipeline(InMemoryStorageProvider.create(), chatModel, 3, CancellationCheckpoint.NONE);

        var thrown = catchThrowable(() -> invokeRefineExtractions(pipeline, List.of(
            chunk("doc-1:0", "zero"),
            chunk("doc-1:1", "one"),
            chunk("doc-1:2", "two")
        )));

        try {
            assertThat(thrown).isSameAs(chatModel.failure());
            assertThat(chatModel.slowChunkThreadTerminatedWithin(Duration.ofSeconds(2))).isTrue();
        } finally {
            chatModel.releaseSlowChunk();
        }
    }

    @Test
    void materializeRebuildUsesConfiguredParallelismEndToEnd() {
        var storage = InMemoryStorageProvider.create();
        for (int order = 0; order < 3; order++) {
            storage.chunkStore().save(new ChunkStore.ChunkRecord(
                "doc-1:" + order,
                "doc-1",
                "text " + order,
                6,
                order,
                Map.of()
            ));
        }
        var chatModel = new RecordingConcurrentChatModel(3);
        var pipeline = newPipeline(storage, chatModel, 3, CancellationCheckpoint.NONE);

        var result = pipeline.materialize("doc-1", GraphMaterializationMode.REBUILD);

        assertThat(result.executedMode()).isEqualTo(GraphMaterializationMode.REBUILD);
        assertThat(chatModel.maxConcurrentCalls()).isGreaterThanOrEqualTo(2);
        assertThat(storage.documentGraphSnapshotStore().listChunks("doc-1"))
            .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
    }

    private static GraphMaterializationPipeline newPipeline(
        InMemoryStorageProvider storage,
        ChatModel chatModel,
        int chunkExtractParallelism,
        CancellationCheckpoint cancellationCheckpoint
    ) {
        return new GraphMaterializationPipeline(
            chatModel,
            new FakeEmbeddingModel(),
            storage,
            ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop(),
            chunkExtractParallelism,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            List.of(),
            List.of(),
            cancellationCheckpoint
        );
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

    private static final class FailingConcurrentChatModel implements ChatModel {
        private final CountDownLatch rendezvous;
        private final CountDownLatch slowRelease = new CountDownLatch(1);
        private final String failingChunkId;
        private final String slowChunkId;
        private final RuntimeException failure;
        private volatile Thread slowChunkThread;

        private FailingConcurrentChatModel(int rendezvousSize, String failingChunkId, String slowChunkId) {
            this.rendezvous = new CountDownLatch(rendezvousSize);
            this.failingChunkId = failingChunkId;
            this.slowChunkId = slowChunkId;
            this.failure = new RuntimeException("boom for " + failingChunkId);
        }

        @Override
        public String generate(ChatRequest request) {
            var currentChunkId = chunkId(request);
            if (slowChunkId.equals(currentChunkId)) {
                slowChunkThread = Thread.currentThread();
            }
            rendezvous.countDown();
            await(rendezvous);
            if (failingChunkId.equals(currentChunkId)) {
                throw failure;
            }
            if (slowChunkId.equals(currentChunkId)) {
                awaitSlowRelease();
            }
            return successResponse(currentChunkId);
        }

        RuntimeException failure() {
            return failure;
        }

        void releaseSlowChunk() {
            slowRelease.countDown();
        }

        boolean slowChunkThreadTerminatedWithin(Duration timeout) {
            var thread = slowChunkThread;
            if (thread == null) {
                return false;
            }
            try {
                thread.join(timeout.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
            return !thread.isAlive();
        }

        private void awaitSlowRelease() {
            try {
                if (!slowRelease.await(30, TimeUnit.SECONDS)) {
                    throw new RuntimeException("slow chunk was never released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("slow chunk interrupted", exception);
            }
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
