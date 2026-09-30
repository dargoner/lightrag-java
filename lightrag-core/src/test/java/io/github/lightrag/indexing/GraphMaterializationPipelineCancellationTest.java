package io.github.lightrag.indexing;

import io.github.lightrag.api.CancellationCheckpoint;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.support.RelationIds;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Cancellation semantics of {@link GraphMaterializationPipeline}.
 *
 * <p>Checkpoints are only polled outside atomic commits, so the contracts pinned here are:
 * a cancel before a commit leaves storage untouched; a cancel arriving during a commit does not
 * interrupt it (the call still commits whole state and returns without polling again afterwards);
 * and no interleaving produces an intra-commit partial state.
 *
 * <p>A rebuild runs two atomic commits (snapshot recovery, then graph materialization), so
 * "snapshot committed, graph not yet" is an accepted inter-commit residue rather than a defect.
 * The invariants asserted below are per-commit: a commit either happens whole or not at all.
 *
 * <p>Poll order of a sequential three-chunk REBUILD, with the checkpoint numbers of the plan:
 * 2 (materialize entry), 4 (rebuildSnapshot entry), 6 (refineExtractions entry),
 * 7 (per chunk, three times), 5 (before the snapshot commit), 3 (materializeDocumentState entry),
 * 10 (before the graph commit). The gap-detector checkpoint (1) is not reached because refinement
 * is disabled in these tests, and the chunk-level points (11, 12) are exercised by repairChunk.
 */
class GraphMaterializationPipelineCancellationTest {
    private static final String WORKSPACE = "default";
    private static final String DOCUMENT_ID = "doc-1";
    private static final String ABSENT_STATUS = "<absent>";

    @Test
    void checkpointBeforeAtomicWriteLeavesStorageUntouched() {
        var storage = InMemoryStorageProvider.create();
        seedMaterializedDocument(storage, DOCUMENT_ID);
        var before = probe(storage, DOCUMENT_ID);
        var checkpoint = new FailingCheckpoint(1);
        var pipeline = newPipeline(storage, new FakeChatModel(), 1, checkpoint);

        var thrown = catchThrowable(() -> pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.REBUILD));

        assertThat(thrown).isSameAs(checkpoint.failure());
        assertThat(checkpoint.calls()).isEqualTo(1);
        assertThat(probe(storage, DOCUMENT_ID)).isEqualTo(before);
    }

    @Test
    void cancellationArrivingDuringAtomicWriteStillCommitsWholeState() {
        var delegate = InMemoryStorageProvider.create();
        seedMaterializedDocument(delegate, DOCUMENT_ID);
        var storage = new CancellationArrivingProvider(delegate);
        var checkpoint = new FlagCheckpoint(storage::isCancellationRequested);
        var pipeline = newPipeline(storage, new FakeChatModel(), 1, checkpoint);

        var result = pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.RESUME);

        assertThat(storage.commitCount()).isEqualTo(1);
        assertThat(storage.cancellationRequestedAtCommit()).isEqualTo(1);
        assertThat(checkpoint.calls()).isGreaterThanOrEqualTo(1);
        assertThat(result.executedMode()).isEqualTo(GraphMaterializationMode.RESUME);
        assertThat(result.finalStatus()).isEqualTo(GraphMaterializationStatus.MERGED);
        var state = probe(delegate, DOCUMENT_ID);
        assertThat(state.chunkSnapshotIds()).hasSize(1);
        assertThat(state.entityIds()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(state.relationIds()).containsExactly(relationId("Alice", "Bob"));
        assertThat(state.entityVectorIds()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(state.relationVectorIds()).containsExactly(relationId("Alice", "Bob"));
        assertThat(state.documentStatus()).isEqualTo(DocumentStatus.PROCESSED.name());
        assertThat(state.documentJournalCount()).isPositive();
        assertThat(state.chunkJournalCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    void materializeNeverLeavesPartialGraphState(int failingCall) {
        var storage = InMemoryStorageProvider.create();
        var chunkIds = List.of(DOCUMENT_ID + ":0", DOCUMENT_ID + ":1", DOCUMENT_ID + ":2");
        seedStoredChunks(storage, chunkIds);
        var checkpoint = new FailingCheckpoint(failingCall);
        var pipeline = newPipeline(storage, new FakeChatModel(), 1, checkpoint);

        var thrown = catchThrowable(() -> pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.REBUILD));

        assertThat(thrown).isSameAs(checkpoint.failure());
        assertThat(checkpoint.calls()).isEqualTo(failingCall);
        assertGraphIsAllOrNothing(probe(storage, DOCUMENT_ID), chunkIds);
    }

    @Test
    void rebuildFromScratchCommitsSnapshotGraphStatusAndJournalsTogether() {
        var storage = InMemoryStorageProvider.create();
        var chunkIds = List.of(DOCUMENT_ID + ":0", DOCUMENT_ID + ":1", DOCUMENT_ID + ":2");
        seedStoredChunks(storage, chunkIds);
        var pipeline = newPipeline(storage, new FakeChatModel(), 1, CancellationCheckpoint.NONE);

        var result = pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.REBUILD);

        assertThat(result.executedMode()).isEqualTo(GraphMaterializationMode.REBUILD);
        assertGraphIsAllOrNothing(probe(storage, DOCUMENT_ID), chunkIds);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void chunkRepairCancellationBeforeTheChunkWriteKeepsStorageUntouched(int failingCall) {
        var storage = InMemoryStorageProvider.create();
        var chunkIds = List.of(DOCUMENT_ID + ":0");
        seedChunkSnapshotWithoutGraph(storage, chunkIds.get(0));
        var before = probe(storage, DOCUMENT_ID);
        var checkpoint = new FailingCheckpoint(failingCall);
        var pipeline = newPipeline(storage, new FakeChatModel(), 1, checkpoint);

        var thrown = catchThrowable(() -> pipeline.repairChunk(DOCUMENT_ID, chunkIds.get(0)));

        assertThat(thrown).isSameAs(checkpoint.failure());
        assertThat(checkpoint.calls()).isEqualTo(failingCall);
        assertThat(probe(storage, DOCUMENT_ID)).isEqualTo(before);
    }

    @Test
    void cancelledRunInterruptsInFlightExtractionAndTerminatesWorkers() {
        var storage = InMemoryStorageProvider.create();
        var chunkIds = List.of(DOCUMENT_ID + ":0", DOCUMENT_ID + ":1");
        seedStoredChunks(storage, chunkIds);
        var chatModel = new InterruptibleBlockingChatModel(chunkIds.size());
        var checkpoint = new CancelsWhenWorkersAreInFlight(chatModel.enteredLatch());
        var pipeline = newPipeline(storage, chatModel, 3, checkpoint);

        var thrown = catchThrowable(() -> pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.REBUILD));

        try {
            assertThat(thrown).isSameAs(checkpoint.failure());
            assertThat(chatModel.interruptedCalls()).isGreaterThanOrEqualTo(1);
            assertThat(chatModel.allWorkerThreadsTerminatedWithin(Duration.ofSeconds(5))).isTrue();
            assertGraphIsAllOrNothing(probe(storage, DOCUMENT_ID), chunkIds);
        } finally {
            chatModel.releaseWorkers();
        }
    }

    @Test
    void shutdownDoesNotBlockLongerThanTheTerminationTimeoutWhenModelIgnoresInterrupts() {
        var storage = InMemoryStorageProvider.create();
        var chunkIds = List.of(DOCUMENT_ID + ":0", DOCUMENT_ID + ":1");
        seedStoredChunks(storage, chunkIds);
        var chatModel = new InterruptIgnoringChatModel(chunkIds.size());
        var checkpoint = new CancelsWhenWorkersAreInFlight(chatModel.enteredLatch());
        var pipeline = newPipeline(storage, chatModel, 3, checkpoint);
        var started = System.nanoTime();

        var thrown = catchThrowable(() -> pipeline.materialize(DOCUMENT_ID, GraphMaterializationMode.REBUILD));

        var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        try {
            assertThat(thrown).isSameAs(checkpoint.failure());
            assertThat(elapsedMillis).isLessThan(10_000L);
            assertGraphIsAllOrNothing(probe(storage, DOCUMENT_ID), chunkIds);
        } finally {
            chatModel.releaseWorkers();
        }
        assertThat(chatModel.allWorkerThreadsTerminatedWithin(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void lightRagMaterializePassesCheckpointIntoPipeline() {
        var storage = InMemoryStorageProvider.create();
        seedStoredChunks(storage, List.of(DOCUMENT_ID + ":0"));
        var checkpoint = new FailingCheckpoint(1);
        try (var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build()) {

            var thrown = catchThrowable(() -> rag.materializeDocumentGraph(
                WORKSPACE,
                DOCUMENT_ID,
                GraphMaterializationMode.REBUILD,
                checkpoint
            ));

            assertThat(thrown).isSameAs(checkpoint.failure());
            assertThat(checkpoint.calls()).isGreaterThan(0);
        }
    }

    private static void assertGraphIsAllOrNothing(StorageProbe state, List<String> expectedChunkIds) {
        var snapshotCommitted = !state.chunkSnapshotIds().isEmpty();
        var graphCommitted = !state.entityIds().isEmpty();
        if (!snapshotCommitted) {
            assertThat(graphCommitted).isFalse();
        } else {
            assertThat(state.chunkSnapshotIds()).containsExactlyInAnyOrderElementsOf(expectedChunkIds);
        }
        if (!graphCommitted) {
            assertThat(state.relationIds()).isEmpty();
            assertThat(state.entityVectorIds()).isEmpty();
            assertThat(state.relationVectorIds()).isEmpty();
            assertThat(state.documentStatus()).isEqualTo(ABSENT_STATUS);
            assertThat(state.documentJournalCount()).isZero();
            assertThat(state.chunkJournalCount()).isZero();
            return;
        }
        assertThat(state.entityIds()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(state.relationIds()).containsExactly(relationId("Alice", "Bob"));
        assertThat(state.entityVectorIds()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(state.relationVectorIds()).containsExactly(relationId("Alice", "Bob"));
        assertThat(state.documentStatus()).isEqualTo(DocumentStatus.PROCESSED.name());
        assertThat(state.documentJournalCount()).isPositive();
        assertThat(state.chunkJournalCount()).isEqualTo(expectedChunkIds.size());
    }

    private static String relationId(String source, String target) {
        return RelationIds.relationId(entityKey(source), entityKey(target));
    }

    private static String entityKey(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static StorageProbe probe(InMemoryStorageProvider storage, String documentId) {
        return new StorageProbe(
            sorted(storage.documentGraphSnapshotStore().listChunks(documentId).stream()
                .map(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
                .toList()),
            sorted(storage.graphStore().allEntities().stream().map(GraphStore.EntityRecord::id).toList()),
            sorted(storage.graphStore().allRelations().stream().map(GraphStore.RelationRecord::id).toList()),
            sorted(storage.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE).stream()
                .map(VectorStore.VectorRecord::id)
                .toList()),
            sorted(storage.vectorStore().list(StorageSnapshots.RELATION_NAMESPACE).stream()
                .map(VectorStore.VectorRecord::id)
                .toList()),
            storage.documentStatusStore().load(documentId).map(record -> record.status().name()).orElse(ABSENT_STATUS),
            storage.documentGraphJournalStore().listDocumentJournals(documentId).size(),
            storage.documentGraphJournalStore().listChunkJournals(documentId).size(),
            sorted(storage.chunkStore().listByDocument(documentId).stream().map(ChunkStore.ChunkRecord::id).toList())
        );
    }

    private static List<String> sorted(List<String> values) {
        var copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }

    private static void seedMaterializedDocument(InMemoryStorageProvider storage, String documentId) {
        try (var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build()) {
            rag.ingest(WORKSPACE, List.of(new Document(documentId, "Title", "Alice works with Bob", Map.of())));
        }
    }

    private static void seedStoredChunks(InMemoryStorageProvider storage, List<String> chunkIds) {
        for (int order = 0; order < chunkIds.size(); order++) {
            storage.chunkStore().save(new ChunkStore.ChunkRecord(
                chunkIds.get(order),
                DOCUMENT_ID,
                "Alice works with Bob " + order,
                8,
                order,
                Map.of()
            ));
        }
    }

    private static void seedChunkSnapshotWithoutGraph(InMemoryStorageProvider storage, String chunkId) {
        var now = Instant.parse("2026-04-12T00:00:00Z");
        storage.documentGraphSnapshotStore().saveDocument(new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            DOCUMENT_ID,
            1,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            1,
            now,
            now,
            null
        ));
        storage.documentGraphSnapshotStore().saveChunks(DOCUMENT_ID, List.of(
            new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                DOCUMENT_ID,
                chunkId,
                0,
                "hash-" + chunkId,
                ChunkExtractStatus.SUCCEEDED,
                List.of(
                    new DocumentGraphSnapshotStore.ExtractedEntityRecord("Alice", "person", "Alice", List.of()),
                    new DocumentGraphSnapshotStore.ExtractedEntityRecord("Bob", "person", "Bob", List.of())
                ),
                List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                    "Alice",
                    "Bob",
                    "works_with",
                    "works with",
                    1.0d
                )),
                now,
                null
            )
        ));
    }

    private static GraphMaterializationPipeline newPipeline(
        AtomicStorageProvider storage,
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

    private record StorageProbe(
        List<String> chunkSnapshotIds,
        List<String> entityIds,
        List<String> relationIds,
        List<String> entityVectorIds,
        List<String> relationVectorIds,
        String documentStatus,
        int documentJournalCount,
        int chunkJournalCount,
        List<String> storedChunkIds
    ) {
    }

    private static final class FailingCheckpoint implements CancellationCheckpoint {
        private final int failingCall;
        private final AtomicInteger calls = new AtomicInteger();
        private final RuntimeException failure = new RuntimeException("cancel requested");

        private FailingCheckpoint(int failingCall) {
            this.failingCall = failingCall;
        }

        @Override
        public void check() {
            if (calls.incrementAndGet() == failingCall) {
                throw failure;
            }
        }

        RuntimeException failure() {
            return failure;
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class FlagCheckpoint implements CancellationCheckpoint {
        private final BooleanSupplier cancellationRequested;
        private final AtomicInteger calls = new AtomicInteger();
        private final RuntimeException failure = new RuntimeException("cancel requested");

        private FlagCheckpoint(BooleanSupplier cancellationRequested) {
            this.cancellationRequested = cancellationRequested;
        }

        @Override
        public void check() {
            calls.incrementAndGet();
            if (cancellationRequested.getAsBoolean()) {
                throw failure;
            }
        }

        int calls() {
            return calls.get();
        }
    }

    private static final class CancelsWhenWorkersAreInFlight implements CancellationCheckpoint {
        private final CountDownLatch workersEntered;
        private final RuntimeException failure = new RuntimeException("cancel requested");

        private CancelsWhenWorkersAreInFlight(CountDownLatch workersEntered) {
            this.workersEntered = workersEntered;
        }

        @Override
        public void check() {
            if (workersEntered.getCount() == 0) {
                throw failure;
            }
        }

        RuntimeException failure() {
            return failure;
        }
    }

    private static final class CancellationArrivingProvider implements AtomicStorageProvider {
        private final InMemoryStorageProvider delegate;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicInteger commitCount = new AtomicInteger();
        private final AtomicInteger cancellationRequestedAtCommit = new AtomicInteger();

        private CancellationArrivingProvider(InMemoryStorageProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            cancellationRequested.set(true);
            cancellationRequestedAtCommit.set(commitCount.incrementAndGet());
            return delegate.writeAtomically(operation);
        }

        boolean isCancellationRequested() {
            return cancellationRequested.get();
        }

        int commitCount() {
            return commitCount.get();
        }

        int cancellationRequestedAtCommit() {
            return cancellationRequestedAtCommit.get();
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            delegate.restore(snapshot);
        }

        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        @Override
        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return delegate.documentGraphSnapshotStore();
        }

        @Override
        public DocumentGraphJournalStore documentGraphJournalStore() {
            return delegate.documentGraphJournalStore();
        }
    }

    private abstract static class BlockingChatModel implements ChatModel {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();

        private BlockingChatModel(int workers) {
            this.entered = new CountDownLatch(workers);
        }

        CountDownLatch enteredLatch() {
            return entered;
        }

        void releaseWorkers() {
            release.countDown();
        }

        boolean allWorkerThreadsTerminatedWithin(Duration timeout) {
            for (var thread : workerThreads) {
                try {
                    thread.join(timeout.toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (thread.isAlive()) {
                    return false;
                }
            }
            return !workerThreads.isEmpty();
        }

        @Override
        public String generate(ChatRequest request) {
            workerThreads.add(Thread.currentThread());
            entered.countDown();
            block(release);
            return successResponse(chunkId(request));
        }

        abstract void block(CountDownLatch release);
    }

    private static final class InterruptibleBlockingChatModel extends BlockingChatModel {
        private final AtomicInteger interruptedCalls = new AtomicInteger();

        private InterruptibleBlockingChatModel(int workers) {
            super(workers);
        }

        @Override
        void block(CountDownLatch release) {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interruptedCalls.incrementAndGet();
                Thread.currentThread().interrupt();
                throw new RuntimeException("model call interrupted", exception);
            }
        }

        int interruptedCalls() {
            return interruptedCalls.get();
        }
    }

    private static final class InterruptIgnoringChatModel extends BlockingChatModel {
        private static final long IGNORED_INTERRUPT_BUDGET_MILLIS = 12_000;

        private InterruptIgnoringChatModel(int workers) {
            super(workers);
        }

        @Override
        void block(CountDownLatch release) {
            var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IGNORED_INTERRUPT_BUDGET_MILLIS);
            while (release.getCount() > 0 && System.nanoTime() < deadline) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // This model deliberately ignores interruption: it only unblocks on release or budget expiry.
                }
            }
        }
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {"entities":[{"name":"Alice","type":"person","description":"Alice","aliases":[]},{"name":"Bob","type":"person","description":"Bob","aliases":[]}],"relations":[{"source_entity":"Alice","target_entity":"Bob","relationship_keywords":"works_with","relationship_description":"works with","weight":1.0}]}
                """;
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

    private static String chunkId(ChatModel.ChatRequest request) {
        var prefix = "Chunk ID: ";
        for (var line : request.userPrompt().lines().toList()) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).strip();
            }
        }
        throw new IllegalStateException("chunk id missing from prompt");
    }

    private static String successResponse(String chunkId) {
        return """
            {"entities":[{"name":"%s","type":"Chunk","description":"%s","aliases":[]}],"relations":[]}
            """.formatted(chunkId, chunkId);
    }
}
