package io.github.lightrag.ops;

import io.github.lightrag.exception.VectorSpaceMismatchException;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.storage.EmbeddingSpaceStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RebuildVectorIndexServiceTest {
    private static final LlmConcurrencyBudget BUDGET =
        new LlmConcurrencyBudget(LlmConcurrencyBudget.DEFAULT_MAX_ASYNC_LLM, LlmConcurrencyBudget.DEFAULT_EMBEDDING_MAX_ASYNC);

    @Test
    void checkModeReportsDriftWithoutWritingVectors() {
        var storage = driftedStorage();
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, false);

        var report = service.check("default");

        assertThat(report.missingEntityVectorIds()).containsExactly("e2");
        assertThat(report.staleEntityVectorIds()).isEmpty();
        assertThat(report.missingChunkVectorIds()).containsExactly("c2");
        assertThat(report.missingRelationVectorIds()).containsExactly("r1");
        assertThat(report.embeddedItems()).isZero();
        assertThat(report.clean()).isFalse();
        assertThat(storage.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(1);
        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE)).hasSize(1);
    }

    @Test
    void rebuildModeRegeneratesAllThreeNamespacesFromTheAuthoritativeSources() {
        var storage = driftedStorage();
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("fake-a", 2, "2026-10-01T00:00:00Z"));
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, false);

        var report = service.rebuild("default");

        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE)).hasSize(2);
        assertThat(storage.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(2);
        assertThat(storage.vectorStore().list(StorageSnapshots.RELATION_NAMESPACE)).hasSize(1);
        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactlyInAnyOrder("c1", "c2");
        assertThat(report.embeddedItems()).isEqualTo(2 + 2 + 1);
        assertThat(report.missingEntityVectorIds()).containsExactly("e2");
        assertThat(service.check("default").clean()).isTrue();
    }

    @Test
    void rebuildEmbeddingsQueueAtLowPriorityBehindQueryEmbeddings() throws Exception {
        var storage = driftedStorage();
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("fake-a", 2, "2026-10-01T00:00:00Z"));
        var budget = new LlmConcurrencyBudget(4, 1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var holderEntered = new java.util.concurrent.CountDownLatch(1);
        var releaseHolder = new java.util.concurrent.CountDownLatch(1);
        var holder = budget.limitEmbedding(LlmConcurrencyBudget.EmbeddingPriority.HIGH, texts -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            order.add("holder");
            return vectors(texts);
        });
        var query = budget.limitEmbedding(LlmConcurrencyBudget.EmbeddingPriority.HIGH, texts -> {
            order.add("query");
            return vectors(texts);
        });
        var recording = new EmbeddingModel() {
            @Override
            public List<List<Double>> embedAll(List<String> texts) {
                order.add("rebuild");
                return texts.stream().map(text -> List.of((double) text.length(), 1.0d)).toList();
            }

            @Override
            public String cacheIdentity() {
                return "fake-a";
            }
        };
        var service = new RebuildVectorIndexService(storage, recording, budget, false);

        var holderThread = new Thread(() -> holder.embedAll(List.of("hold")));
        holderThread.start();
        assertThat(holderEntered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var rebuildFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var rebuildThread = new Thread(() -> {
            try {
                service.rebuild("default");
            } catch (Throwable throwable) {
                rebuildFailure.set(throwable);
            }
        });
        rebuildThread.start();
        awaitParked(rebuildThread);
        var queryThread = new Thread(() -> query.embedAll(List.of("query")));
        queryThread.start();
        awaitParked(queryThread);
        try {
            releaseHolder.countDown();
            holderThread.join(5_000L);
            rebuildThread.join(5_000L);
            queryThread.join(5_000L);
            assertThat(holderThread.isAlive()).isFalse();
            assertThat(rebuildThread.isAlive()).isFalse();
            assertThat(queryThread.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
        }

        assertThat(rebuildFailure.get()).isNull();
        // The rebuild path must claim the LOW priority: the query request overtakes it even though
        // the rebuild enqueued first (upstream query embeddings at 5 vs ingestion at 10,
        // operate.py:5347-5349). Chunks, entities and relations embed in one batch each.
        assertThat(order).containsExactly("holder", "query", "rebuild", "rebuild", "rebuild");
    }

    @Test
    void rebuildReplacesStaleVectorsAndClearsTheMarker() {
        var storage = driftedStorage();
        storage.vectorStore().saveAll(
            StorageSnapshots.RELATION_NAMESPACE,
            List.of(new VectorStore.VectorRecord("r-stale", List.of(1.0, 2.0)))
        );
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("fake-a", 2, "2026-10-01T00:00:00Z"));
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, false);

        var report = service.rebuild("default");

        assertThat(report.staleRelationVectorIds()).containsExactly("r-stale");
        assertThat(report.staleItems()).isEqualTo(1);
        assertThat(storage.vectorStore().list(StorageSnapshots.RELATION_NAMESPACE))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("r1");
        assertThat(storage.embeddingSpaceStore().load()).isEmpty();
        assertThat(service.check("default").clean()).isTrue();
    }

    @Test
    void rebuildRefusesToRunWithoutForceWhenTheEmbeddingSpaceMarkerIsMissing() {
        var storage = driftedStorage();
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, false);

        assertThatThrownBy(() -> service.rebuild("default"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("--force");

        assertThat(storage.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(1);
    }

    @Test
    void rebuildRefusesWhenTheRecordedMarkerDescribesADifferentEmbeddingModel() {
        var storage = driftedStorage();
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("other-model", 2, "2026-10-01T00:00:00Z"));
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, false);

        assertThatThrownBy(() -> service.rebuild("default"))
            .isInstanceOf(VectorSpaceMismatchException.class)
            .hasMessageContaining("other-model")
            .hasMessageContaining("--force");
    }

    @Test
    void rebuildWithForceRegeneratesDespiteADifferentRecordedMarker() {
        var storage = driftedStorage();
        storage.embeddingSpaceStore().save(new EmbeddingSpaceStore.Marker("other-model", 2, "2026-10-01T00:00:00Z"));
        var service = new RebuildVectorIndexService(storage, new FakeEmbeddingModel("fake-a"), BUDGET, true);

        var report = service.rebuild("default");

        assertThat(report.embeddedItems()).isEqualTo(5);
        assertThat(storage.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(2);
        assertThat(storage.embeddingSpaceStore().load()).isEmpty();
    }

    @Test
    void rebuildSurvivesASnapshotFileRoundTrip() throws java.io.IOException {
        var fixtureDirectory = java.nio.file.Path.of("build", "rebuild-vdb-fixture").toAbsolutePath();
        var driftedFixture = fixtureDirectory.resolve("drifted-store.json");
        var rebuiltFixture = fixtureDirectory.resolve("rebuilt-store.json");
        var snapshotStore = new io.github.lightrag.persistence.FileSnapshotStore();
        snapshotStore.save(driftedFixture, StorageSnapshots.capture(driftedStorage()));

        var reloaded = InMemoryStorageProvider.create(snapshotStore);
        reloaded.restore(snapshotStore.load(driftedFixture));
        var service = new RebuildVectorIndexService(reloaded, new FakeEmbeddingModel("fake-a"), BUDGET, true);
        assertThat(service.check("default").missingEntityVectorIds()).containsExactly("e2");

        assertThat(service.rebuild("default").embeddedItems()).isEqualTo(5);
        snapshotStore.save(rebuiltFixture, StorageSnapshots.capture(reloaded));

        var verified = InMemoryStorageProvider.create(snapshotStore);
        verified.restore(snapshotStore.load(rebuiltFixture));
        var verifyService = new RebuildVectorIndexService(verified, new FakeEmbeddingModel("fake-a"), BUDGET, true);
        assertThat(verifyService.check("default").clean()).isTrue();
        assertThat(verified.vectorStore().list(StorageSnapshots.ENTITY_NAMESPACE)).hasSize(2);
        assertThat(verified.vectorStore().list(StorageSnapshots.RELATION_NAMESPACE)).hasSize(1);
    }

    @Test
    void parsesSpaceSeparatedAndEqualsSeparatedCommandOptions() {
        var parsed = RebuildVectorIndexCommand.parseArgs(new String[] {
            "--mode", "rebuild", "--snapshot-file=build/store.json", "--workspace", "default", "--force"
        });

        assertThat(parsed)
            .containsEntry("--mode", "rebuild")
            .containsEntry("--snapshot-file", "build/store.json")
            .containsEntry("--workspace", "default")
            .containsEntry("--force", "true");
        assertThatThrownBy(() -> RebuildVectorIndexCommand.parseArgs(new String[] {"--mode"}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--mode");
    }

    private static InMemoryStorageProvider driftedStorage() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord("c1", "doc-1", "alpha", 1, 0, java.util.Map.of()));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord("c2", "doc-1", "beta", 1, 1, java.util.Map.of()));
        storage.graphStore().saveEntity(entity("e1"));
        storage.graphStore().saveEntity(entity("e2"));
        storage.graphStore().saveRelation(relation("r1", "e1", "e2"));
        storage.vectorStore().saveAll(
            StorageSnapshots.CHUNK_NAMESPACE,
            List.of(new VectorStore.VectorRecord("c1", List.of(0.5, 0.5)))
        );
        storage.vectorStore().saveAll(
            StorageSnapshots.ENTITY_NAMESPACE,
            List.of(new VectorStore.VectorRecord("e1", List.of(0.5, 0.5)))
        );
        return storage;
    }

    private static GraphStore.EntityRecord entity(String id) {
        return new GraphStore.EntityRecord(id, id.toUpperCase(java.util.Locale.ROOT), "PERSON", "description of " + id, List.of(), List.of("c1"));
    }

    private static GraphStore.RelationRecord relation(String id, String srcId, String tgtId) {
        return new GraphStore.RelationRecord(id, srcId, tgtId, "works_with", "description of " + id, 1.0, "c1", "");
    }

    private static List<List<Double>> vectors(List<String> texts) {
        return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
    }

    private static void awaitQuietly(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for latch release", exception);
        }
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        private final String identity;

        private FakeEmbeddingModel(String identity) {
            this.identity = identity;
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> List.of((double) text.length(), 1.0))
                .toList();
        }

        @Override
        public String cacheIdentity() {
            return identity;
        }
    }
}
