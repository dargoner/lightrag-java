package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.PreChunkedChunk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the workspace concurrency contract of the task entry points: single-document work overlaps up to
 * {@code maxConcurrentDocumentTasks}, while batch, rebuild and delete work never overlaps another task. The chat
 * model double holds a task inside extraction so a second submission either reaches the model concurrently
 * (document scoped) or waits at the workspace gate (workspace exclusive).
 */
class LightRagDocumentConcurrencyTest {
    private static final String WORKSPACE = "document-concurrency";
    private static final String ALPHA_MARKER = "Alpha meets Alice";
    private static final String BETA_MARKER = "Beta meets Bob";
    private static final String GAMMA_MARKER = "Gamma meets Carol";
    private static final String FOO_MARKER = "Shared meets Foo";
    private static final String BAR_MARKER = "Shared meets Bar";

    @Test
    void twoConcurrentDocumentIngestsInOneWorkspaceBothSucceed() {
        var storage = InMemoryStorageProvider.create();
        var extractionBarrier = new CyclicBarrier(2);
        var rag = LightRag.builder()
            .chatModel(new ScriptedChatModel(
                Map.of(
                    ALPHA_MARKER, entityExtraction("Alice"),
                    BETA_MARKER, entityExtraction("Bob")
                ),
                Map.of(
                    ALPHA_MARKER, () -> awaitBothCallers(extractionBarrier),
                    BETA_MARKER, () -> awaitBothCallers(extractionBarrier)
                )
            ))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .maxConcurrentDocumentTasks(2)
            .build();

        var firstTaskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(preChunkedChunk("doc-alpha", "chunk-alpha", ALPHA_MARKER))
        );
        var secondTaskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(preChunkedChunk("doc-beta", "chunk-beta", BETA_MARKER))
        );

        var firstTask = awaitTerminalTask(rag, firstTaskId);
        var secondTask = awaitTerminalTask(rag, secondTaskId);

        assertThat(firstTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(secondTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("bob")).isPresent();
    }

    @Test
    void rebuildWaitsForConcurrentDocumentIngests() {
        var storage = InMemoryStorageProvider.create();
        var ingestsInModel = new CountDownLatch(2);
        var releaseIngests = new CountDownLatch(1);
        var rag = LightRag.builder()
            .chatModel(new ScriptedChatModel(
                Map.of(
                    ALPHA_MARKER, entityExtraction("Alice"),
                    BETA_MARKER, entityExtraction("Bob")
                ),
                Map.of(
                    ALPHA_MARKER, () -> holdIngest(ingestsInModel, releaseIngests),
                    BETA_MARKER, () -> holdIngest(ingestsInModel, releaseIngests)
                )
            ))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            // The third permit stays free, so a rebuild that wrongly ran document scoped would start right away
            // instead of waiting for a permit; that is what makes the ordering assertion deterministic.
            .maxConcurrentDocumentTasks(3)
            .build();

        var firstIngestTaskId = rag.submitIngest(WORKSPACE, List.of(document("doc-alpha", ALPHA_MARKER)));
        var secondIngestTaskId = rag.submitIngest(WORKSPACE, List.of(document("doc-beta", BETA_MARKER)));
        awaitLatch(ingestsInModel, "both ingests to reach extraction");

        var rebuildTaskId = rag.submitRebuild(WORKSPACE);
        releaseIngests.countDown();

        var firstIngest = awaitTerminalTask(rag, firstIngestTaskId);
        var secondIngest = awaitTerminalTask(rag, secondIngestTaskId);
        var rebuild = awaitTerminalTask(rag, rebuildTaskId);

        assertThat(firstIngest.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(secondIngest.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(rebuild.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(rebuild.startedAt()).isAfterOrEqualTo(latest(firstIngest.finishedAt(), secondIngest.finishedAt()));
    }

    @Test
    void deleteByDocumentIdWaitsForConcurrentDocumentIngests() {
        var storage = InMemoryStorageProvider.create();
        var ingestsInModel = new CountDownLatch(2);
        var releaseIngests = new CountDownLatch(1);
        var rag = LightRag.builder()
            .chatModel(new ScriptedChatModel(
                Map.of(
                    ALPHA_MARKER, entityExtraction("Alice"),
                    BETA_MARKER, entityExtraction("Bob")
                ),
                Map.of(
                    ALPHA_MARKER, () -> holdIngest(ingestsInModel, releaseIngests),
                    BETA_MARKER, () -> holdIngest(ingestsInModel, releaseIngests)
                )
            ))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            // The third permit stays free, so a delete that wrongly ran document scoped would start right away
            // instead of waiting for a permit; that is what makes the ordering assertion deterministic.
            .maxConcurrentDocumentTasks(3)
            .build();

        var firstIngestTaskId = rag.submitIngest(WORKSPACE, List.of(document("doc-alpha", ALPHA_MARKER)));
        var secondIngestTaskId = rag.submitIngest(WORKSPACE, List.of(document("doc-beta", BETA_MARKER)));
        awaitLatch(ingestsInModel, "both ingests to reach extraction");

        var deleteTaskId = rag.submitDeleteByDocumentId(WORKSPACE, "doc-beta");
        releaseIngests.countDown();

        var firstIngest = awaitTerminalTask(rag, firstIngestTaskId);
        var secondIngest = awaitTerminalTask(rag, secondIngestTaskId);
        var delete = awaitTerminalTask(rag, deleteTaskId);

        assertThat(firstIngest.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(secondIngest.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(delete.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(delete.startedAt()).isAfterOrEqualTo(latest(firstIngest.finishedAt(), secondIngest.finishedAt()));
    }

    @Test
    void multiDocumentBatchIngestStaysExclusiveAgainstASingleDocumentTask() {
        var storage = InMemoryStorageProvider.create();
        var batchInModel = new CountDownLatch(1);
        var releaseBatch = new CountDownLatch(1);
        var singleTaskInModel = new CountDownLatch(1);
        var chatModel = new ScriptedChatModel(
            Map.of(
                ALPHA_MARKER, entityExtraction("Alice"),
                BETA_MARKER, entityExtraction("Bob"),
                GAMMA_MARKER, entityExtraction("Carol")
            ),
            Map.of(
                ALPHA_MARKER, () -> {
                    batchInModel.countDown();
                    awaitRelease(releaseBatch);
                },
                GAMMA_MARKER, singleTaskInModel::countDown
            )
        );
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .maxParallelInsert(1)
            .maxConcurrentDocumentTasks(2)
            .build();

        var batchTaskId = rag.submitIngest(WORKSPACE, List.of(
            document("doc-one", ALPHA_MARKER),
            document("doc-two", BETA_MARKER)
        ));
        awaitLatch(batchInModel, "the multi-document batch to reach extraction");

        var singleTaskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(preChunkedChunk("doc-three", "chunk-three", GAMMA_MARKER))
        );
        var singleTaskOverlappedTheBatch = awaitWithin(singleTaskInModel, 750L);
        releaseBatch.countDown();

        var batchTask = awaitTerminalTask(rag, batchTaskId);
        var singleTask = awaitTerminalTask(rag, singleTaskId);

        assertThat(singleTaskOverlappedTheBatch)
            .as("a two-document batch request must stay workspace exclusive")
            .isFalse();
        assertThat(batchTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(singleTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(chatModel.peakConcurrency())
            .as("extraction calls must never overlap across tasks")
            .isEqualTo(1);
    }

    @Test
    void twoConcurrentDocumentIngestsSharingAnEntityMergeRatherThanLose() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new ScriptedChatModel(
                Map.of(
                    FOO_MARKER, """
                        {
                          "entities": [
                            {"name": "Shared", "type": "thing", "description": "Shared by both documents", "aliases": []},
                            {"name": "Foo", "type": "thing", "description": "Foo", "aliases": []}
                          ],
                          "relations": [
                            {
                              "source_entity": "Shared",
                              "target_entity": "Foo",
                              "relationship_keywords": "meets",
                              "relationship_description": "shared meets foo",
                              "weight": 1.0
                            }
                          ]
                        }
                        """,
                    BAR_MARKER, """
                        {
                          "entities": [
                            {"name": "Shared", "type": "thing", "description": "Shared by both documents", "aliases": []},
                            {"name": "Bar", "type": "thing", "description": "Bar", "aliases": []}
                          ],
                          "relations": [
                            {
                              "source_entity": "Shared",
                              "target_entity": "Bar",
                              "relationship_keywords": "meets",
                              "relationship_description": "shared meets bar",
                              "weight": 1.0
                            }
                          ]
                        }
                        """
                ),
                Map.of()
            ))
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .maxConcurrentDocumentTasks(2)
            .build();

        var firstTaskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(preChunkedChunk("doc-one", "chunk-one", FOO_MARKER))
        );
        var secondTaskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(preChunkedChunk("doc-two", "chunk-two", BAR_MARKER))
        );

        var firstTask = awaitTerminalTask(rag, firstTaskId);
        var secondTask = awaitTerminalTask(rag, secondTaskId);

        assertThat(firstTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(secondTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        var sharedNodes = storage.graphStore().allEntities().stream()
            .filter(entity -> "shared".equals(entity.id()))
            .toList();
        assertThat(sharedNodes).hasSize(1);
        assertThat(sharedNodes.get(0).sourceChunkIds())
            .as("concurrent document ingests must merge entities instead of overwriting them")
            .containsExactlyInAnyOrder("chunk-one", "chunk-two");
    }

    @Test
    void capsConcurrentExtractionCallsAcrossDocuments() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new ScriptedChatModel(
            Map.of(
                ALPHA_MARKER, entityExtraction("Alice"),
                BETA_MARKER, entityExtraction("Bob"),
                GAMMA_MARKER, entityExtraction("Carol")
            ),
            Map.of(
                ALPHA_MARKER, () -> sleepQuietly(100L),
                BETA_MARKER, () -> sleepQuietly(100L),
                GAMMA_MARKER, () -> sleepQuietly(100L)
            )
        );
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .chunkExtractParallelism(1)
            .maxParallelInsert(3)
            .maxAsyncLlm(1)
            .build();

        var taskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(
                preChunkedChunk("doc-alpha", "chunk-alpha", ALPHA_MARKER),
                preChunkedChunk("doc-beta", "chunk-beta", BETA_MARKER),
                preChunkedChunk("doc-gamma", "chunk-gamma", GAMMA_MARKER)
            )
        );

        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(chatModel.peakConcurrency())
            .as("maxAsyncLlm(1) must serialize LLM calls across the documents of one ingest batch")
            .isEqualTo(1);
    }

    @Test
    void capsConcurrentEmbeddingCallsAcrossDocuments() {
        var storage = InMemoryStorageProvider.create();
        var embeddingModel = new ConcurrencyRecordingEmbeddingModel();
        var chatModel = new ScriptedChatModel(
            Map.of(
                ALPHA_MARKER, entityExtraction("Alice"),
                BETA_MARKER, entityExtraction("Bob"),
                GAMMA_MARKER, entityExtraction("Carol")
            ),
            Map.of()
        );
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .storage(storage)
            .chunkExtractParallelism(1)
            .maxParallelInsert(3)
            .embeddingMaxAsync(1)
            .build();

        var taskId = rag.submitIngestChunks(
            WORKSPACE,
            List.of(
                preChunkedChunk("doc-alpha", "chunk-alpha", ALPHA_MARKER),
                preChunkedChunk("doc-beta", "chunk-beta", BETA_MARKER),
                preChunkedChunk("doc-gamma", "chunk-gamma", GAMMA_MARKER)
            )
        );

        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(embeddingModel.calls()).isGreaterThanOrEqualTo(2);
        assertThat(embeddingModel.peakConcurrency())
            .as("embeddingMaxAsync(1) must serialize embedding calls across the documents of one ingest batch")
            .isEqualTo(1);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test interrupted", exception);
        }
    }

    private static TaskSnapshot awaitTerminalTask(LightRag rag, String taskId) {
        var deadline = Instant.now().plus(java.time.Duration.ofSeconds(20));
        var snapshot = rag.getTask(WORKSPACE, taskId);
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test interrupted", exception);
            }
            snapshot = rag.getTask(WORKSPACE, taskId);
        }
        assertThat(snapshot.status().isTerminal()).isTrue();
        return snapshot;
    }

    private static Instant latest(Instant first, Instant second) {
        return first.isAfter(second) ? first : second;
    }

    private static void awaitLatch(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + description, exception);
        }
    }

    private static boolean awaitWithin(CountDownLatch latch, long millis) {
        try {
            return latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while observing the workspace gate", exception);
        }
    }

    private static void awaitBothCallers(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("extraction barrier interrupted", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new IllegalStateException("both document ingests were expected to reach extraction concurrently", exception);
        }
    }

    /** Holds the ingest inside extraction; a gate that times out fails the task instead of hanging the suite. */
    private static void holdIngest(CountDownLatch ingestsInModel, CountDownLatch releaseIngests) {
        ingestsInModel.countDown();
        awaitRelease(releaseIngests);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test did not release the held extraction in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("held extraction interrupted", exception);
        }
    }

    private static Document document(String documentId, String text) {
        return new Document(documentId, "Title", text, Map.of("source", "document-concurrency-test"));
    }

    private static PreChunkedChunk preChunkedChunk(String documentId, String chunkId, String text) {
        return new PreChunkedChunk(
            documentId,
            "Title",
            new Chunk(chunkId, documentId, text, 4, 0, Map.of("source", "document-concurrency-test")),
            Map.of("source", "document-concurrency-test")
        );
    }

    private static String entityExtraction(String... entityNames) {
        var entities = new ArrayList<String>();
        for (var name : entityNames) {
            entities.add("{\"name\":\"" + name + "\",\"type\":\"person\",\"description\":\"" + name + "\",\"aliases\":[]}");
        }
        return "{\"entities\":[" + String.join(",", entities) + "],\"relations\":[]}";
    }

    /**
     * Chat model double: answers each extraction prompt with the payload registered for the marker found in the
     * prompt text, runs that marker's entry action on its first call, and records the peak number of overlapping
     * {@code generate} calls.
     */
    private static final class ScriptedChatModel implements ChatModel {
        private static final String EMPTY_EXTRACTION = "{\"entities\":[],\"relations\":[]}";

        private final Map<String, String> responsesByMarker;
        private final Map<String, Runnable> firstCallActions;
        private final Set<String> seenMarkers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger peakConcurrency = new AtomicInteger();

        private ScriptedChatModel(Map<String, String> responsesByMarker, Map<String, Runnable> firstCallActions) {
            this.responsesByMarker = Map.copyOf(responsesByMarker);
            this.firstCallActions = Map.copyOf(firstCallActions);
        }

        @Override
        public String generate(ChatModel.ChatRequest request) {
            var active = activeCalls.incrementAndGet();
            peakConcurrency.accumulateAndGet(active, Math::max);
            try {
                var marker = matchingMarker(request.userPrompt());
                if (marker != null && seenMarkers.add(marker)) {
                    firstCallActions.getOrDefault(marker, () -> { }).run();
                }
                return marker == null ? EMPTY_EXTRACTION : responsesByMarker.get(marker);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        private int peakConcurrency() {
            return peakConcurrency.get();
        }

        private String matchingMarker(String userPrompt) {
            var prompt = userPrompt == null ? "" : userPrompt;
            return responsesByMarker.keySet().stream()
                .filter(prompt::contains)
                .findFirst()
                .orElse(null);
        }
    }

    private static final class ConcurrencyRecordingEmbeddingModel implements EmbeddingModel {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(100L);
                return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private int calls() {
            return calls.get();
        }

        private int peakConcurrency() {
            return peak.get();
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
        }
    }
}
