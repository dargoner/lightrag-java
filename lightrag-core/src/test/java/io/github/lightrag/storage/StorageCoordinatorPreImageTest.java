package io.github.lightrag.storage;

import io.github.lightrag.storage.StorageAssemblyTestDoubles.FakeRelationalStorageAdapter;
import io.github.lightrag.storage.StorageAssemblyTestDoubles.ScopedGraphStorageAdapter;
import io.github.lightrag.storage.StorageAssemblyTestDoubles.ScopedGraphStorageAdapter.CaptureRequest;
import io.github.lightrag.storage.StorageAssemblyTestDoubles.ScopedVectorStorageAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the scoped pre-image contract of {@link StorageCoordinator#writeAtomically}: the write path captures no full
 * snapshots, compensation restores the exact pre-image state of every applied side, legacy adapters fall back to
 * snapshots, and the accumulator keeps first-seen pre-images across PostgreSQL retries while replaying every
 * captured payload on compensation. Every compensation assertion compares final storage state — never call counts.
 */
class StorageCoordinatorPreImageTest {
    private final FakeRelationalStorageAdapter relational = new FakeRelationalStorageAdapter();
    private final ScopedGraphStorageAdapter graph = new ScopedGraphStorageAdapter();
    private final ScopedVectorStorageAdapter vector = new ScopedVectorStorageAdapter();

    @Test
    void doesNotCaptureFullSnapshotsOnTheWritePath() {
        graph.seedEntity(entity("e0", "seed"));
        vector.seed("chunks", vectorWrite("c0", "seed", 1.0d));

        provider().writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            storage.graphStore().saveRelation(relation("r1", "e1", "e0", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        });

        assertThat(graph.captureSnapshotCount()).isZero();
        assertThat(vector.captureSnapshotCount()).isZero();
        assertThat(graph.preImageRequests()).containsExactly(new CaptureRequest(List.of("e1"), List.of("r1")));
        assertThat(vector.preImageRequests()).containsExactly(Map.of("chunks", List.of("c1")));
        assertThat(graph.restoreCalls()).isEmpty();
        assertThat(vector.restoreCalls()).isEmpty();
        assertThat(graph.storedEntity("e1")).contains(entity("e1", "one"));
        assertThat(graph.storedRelation("r1")).contains(relation("r1", "e1", "e0", "one"));
        assertThat(vector.storedWrite("chunks", "c1")).contains(vectorWrite("c1", "one", 0.5d));
    }

    @Test
    void compensatesOnlyTheSideThatWasApplied() {
        var seedEntity = entity("e0", "seed");
        var seedVector = vectorWrite("c0", "seed", 1.0d);
        graph.seedEntity(seedEntity);
        vector.seed("chunks", seedVector);
        graph.failOnNthWrite(1);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #1");

        assertThat(graph.applyCount()).isEqualTo(1);
        assertThat(vector.applyCount()).isZero();
        assertThat(graph.restoreCalls()).hasSize(1);
        assertThat(vector.restoreCalls()).isEmpty();
        assertThat(graph.restoreCount()).isZero();
        assertThat(vector.captureSnapshotCount()).isZero();
        assertThat(graph.storedEntities()).containsExactly(seedEntity);
        assertThat(vector.storedWrites("chunks")).containsExactly(seedVector);
    }

    @Test
    void compensatesBothSidesWhenVectorApplyFails() {
        var seedEntity = entity("e0", "seed");
        var seedRelation = relation("r0", "e0", "e0", "seed");
        var seedVector = vectorWrite("c0", "seed", 1.0d);
        graph.seedEntity(seedEntity);
        graph.seedRelation(seedRelation);
        vector.seed("chunks", seedVector);
        vector.failOnNthWrite(1);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            storage.graphStore().saveRelation(relation("r1", "e1", "e0", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #1");

        assertThat(graph.applyCount()).isEqualTo(1);
        assertThat(vector.applyCount()).isEqualTo(1);
        assertThat(graph.restoreCalls()).hasSize(1);
        assertThat(vector.restoreCalls()).hasSize(1);
        assertThat(graph.storedEntities()).containsExactly(seedEntity);
        assertThat(graph.storedRelations()).containsExactly(seedRelation);
        assertThat(vector.storedWrites("chunks")).containsExactly(seedVector);
    }

    @Test
    void compensatesWhenTransactionCommitFails() {
        var seedDocument = new DocumentStore.DocumentRecord("doc-0", "seed", "seed", Map.of("seed", "true"));
        var seedEntity = entity("e0", "seed");
        var seedVector = vectorWrite("c0", "seed", 1.0d);
        relational.documentStore().save(seedDocument);
        graph.seedEntity(seedEntity);
        vector.seed("chunks", seedVector);
        var commitFailure = new IllegalStateException("commit-failed");
        relational.failNextCommit(commitFailure);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "incoming", "body", Map.of()));
            storage.graphStore().saveEntity(entity("e1", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        })).isSameAs(commitFailure);

        assertThat(relational.attempts()).isEqualTo(1);
        assertThat(relational.restoreCount()).isZero();
        assertThat(relational.documentStore().list()).containsExactly(seedDocument);
        assertThat(graph.storedEntities()).containsExactly(seedEntity);
        assertThat(vector.storedWrites("chunks")).containsExactly(seedVector);
        assertThat(graph.restoreCalls()).hasSize(1);
        assertThat(vector.restoreCalls()).hasSize(1);
    }

    @Test
    void skipsCompensationWhenOperationFailsBeforeAnyApply() {
        var seedEntity = entity("e0", "seed");
        var seedVector = vectorWrite("c0", "seed", 1.0d);
        graph.seedEntity(seedEntity);
        vector.seed("chunks", seedVector);
        var operationFailure = new IllegalStateException("operation-failed");

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            throw operationFailure;
        })).isSameAs(operationFailure);

        assertThat(graph.restoreCalls()).isEmpty();
        assertThat(vector.restoreCalls()).isEmpty();
        assertThat(graph.restoreCount()).isZero();
        assertThat(vector.restoreCount()).isZero();
        assertThat(graph.storedEntities()).containsExactly(seedEntity);
        assertThat(vector.storedWrites("chunks")).containsExactly(seedVector);
    }

    @Test
    void fallsBackToFullSnapshotForLegacyAdapters() {
        var legacyRelational = new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter();
        var legacyGraph = new StorageAssemblyTestDoubles.FakeGraphStorageAdapter();
        var legacyVector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();
        var provider = StorageAssembly.builder()
            .relationalAdapter(legacyRelational)
            .graphAdapter(legacyGraph)
            .vectorAdapter(legacyVector)
            .build()
            .toStorageProvider();

        var seedEntity = entity("e0", "seed");
        var seedRelation = relation("r0", "e0", "e0", "seed");
        var seedVector = new VectorStore.VectorRecord("c0", List.of(1.0d, 0.0d));
        legacyGraph.graphStore().saveEntity(seedEntity);
        legacyGraph.graphStore().saveRelation(seedRelation);
        legacyVector.vectorStore().saveAll("chunks", List.of(seedVector));

        legacyRelational.failNextCommitTransiently();
        var attempt = new AtomicInteger();
        var retryFailure = new IllegalStateException("graph-apply-failed-on-retry");

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            if (attempt.incrementAndGet() == 2) {
                legacyGraph.failOnApply(retryFailure);
            }
            storage.graphStore().saveEntity(entity("e1", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        })).isSameAs(retryFailure);

        assertThat(legacyRelational.attempts()).isEqualTo(2);
        assertThat(legacyGraph.captureSnapshotCount()).isEqualTo(1);
        assertThat(legacyVector.captureSnapshotCount()).isEqualTo(1);
        assertThat(legacyGraph.restoreCount()).isEqualTo(1);
        assertThat(legacyVector.restoreCount()).isEqualTo(1);
        assertThat(legacyGraph.graphStore().allEntities()).containsExactly(seedEntity);
        assertThat(legacyGraph.graphStore().allRelations()).containsExactly(seedRelation);
        assertThat(legacyVector.vectorStore().list("chunks")).containsExactly(seedVector);
    }

    @Test
    void supportsScopedGraphWithFallbackVector() {
        var legacyVector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();
        var provider = StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(legacyVector)
            .build()
            .toStorageProvider();

        var seedEntity = entity("e0", "seed");
        var seedVector = new VectorStore.VectorRecord("c0", List.of(1.0d, 0.0d));
        graph.seedEntity(seedEntity);
        legacyVector.vectorStore().saveAll("chunks", List.of(seedVector));
        var commitFailure = new IllegalStateException("commit-failed");
        relational.failNextCommit(commitFailure);

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        })).isSameAs(commitFailure);

        assertThat(graph.captureSnapshotCount()).isZero();
        assertThat(graph.restoreCalls()).hasSize(1);
        assertThat(graph.restoreCount()).isZero();
        assertThat(legacyVector.captureSnapshotCount()).isEqualTo(1);
        assertThat(legacyVector.restoreCount()).isEqualTo(1);
        assertThat(graph.storedEntities()).containsExactly(seedEntity);
        assertThat(legacyVector.vectorStore().list("chunks")).containsExactly(seedVector);
    }

    @Test
    void capturesPreImageOnlyForIdsNotYetCapturedAcrossRetries() {
        graph.seedEntity(entity("e0", "seed"));
        relational.failNextCommitTransiently();
        var attempt = new AtomicInteger();

        provider().writeAtomically(storage -> {
            if (attempt.incrementAndGet() == 1) {
                storage.graphStore().saveEntity(entity("e1", "one"));
            } else {
                storage.graphStore().saveEntity(entity("e1", "one"));
                storage.graphStore().saveEntity(entity("e2", "two"));
            }
            return null;
        });

        assertThat(relational.attempts()).isEqualTo(2);
        assertThat(graph.preImageRequests()).containsExactly(
            new CaptureRequest(List.of("e1"), List.of()),
            new CaptureRequest(List.of("e2"), List.of())
        );
        assertThat(graph.captureSnapshotCount()).isZero();
        assertThat(graph.storedEntity("e1")).contains(entity("e1", "one"));
        assertThat(graph.storedEntity("e2")).contains(entity("e2", "two"));
    }

    @Test
    void keepsFirstPreImageWhenRetryWidensTheWriteSet() {
        var seedEntity = entity("e1", "old");
        graph.seedEntity(entity("e0", "seed"));
        graph.seedEntity(seedEntity);
        relational.failNextCommitTransiently();
        var attempt = new AtomicInteger();

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            var round = attempt.incrementAndGet();
            storage.graphStore().saveEntity(entity("e1", round == 1 ? "first-attempt" : "second-attempt"));
            if (round == 2) {
                graph.failOnNthWrite(2);
                storage.graphStore().saveEntity(entity("e2", "added-on-retry"));
            }
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #2");

        assertThat(relational.attempts()).isEqualTo(2);
        assertThat(graph.preImageRequests()).containsExactly(
            new CaptureRequest(List.of("e1"), List.of()),
            new CaptureRequest(List.of("e2"), List.of())
        );
        assertThat(graph.restoreCalls()).hasSize(2);
        assertThat(graph.storedEntity("e1")).contains(seedEntity);
        assertThat(graph.storedEntity("e2")).isEmpty();
    }

    @Test
    void keepsFirstPreImageWhenRetryChangesRelationAndVectorSets() {
        var seedEntity = entity("e1", "old");
        var seedRelation = relation("r1", "e1", "e1", "old");
        var seedVector = vectorWrite("c1", "old", 1.0d);
        graph.seedEntity(seedEntity);
        graph.seedRelation(seedRelation);
        vector.seed("chunks", seedVector);
        relational.failNextCommitTransiently();
        var attempt = new AtomicInteger();

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            var round = attempt.incrementAndGet();
            var marker = round == 1 ? "first-attempt" : "second-attempt";
            storage.graphStore().saveEntity(entity("e1", marker));
            storage.graphStore().saveRelation(relation("r1", "e1", "e1", marker));
            enrichedSave(storage, enriched("c1", marker, 0.5d));
            if (round == 2) {
                graph.failOnNthWrite(3);
                storage.graphStore().saveEntity(entity("e2", "new"));
                storage.graphStore().saveRelation(relation("r2", "e1", "e2", "new"));
                enrichedSave(storage, enriched("c2", "new", 0.25d));
            }
            return null;
        })).isInstanceOf(IllegalStateException.class);

        assertThat(relational.attempts()).isEqualTo(2);
        assertThat(graph.preImageRequests()).containsExactly(
            new CaptureRequest(List.of("e1"), List.of("r1")),
            new CaptureRequest(List.of("e2"), List.of("r2"))
        );
        assertThat(vector.preImageRequests()).containsExactly(
            Map.of("chunks", List.of("c1")),
            Map.of("chunks", List.of("c2"))
        );
        assertThat(graph.restoreCalls()).hasSize(2);
        assertThat(vector.restoreCalls()).hasSize(2);
        assertThat(graph.storedEntity("e1")).contains(seedEntity);
        assertThat(graph.storedRelation("r1")).contains(seedRelation);
        assertThat(vector.storedWrite("chunks", "c1")).contains(seedVector);
        assertThat(graph.storedEntity("e2")).isEmpty();
        assertThat(graph.storedRelation("r2")).isEmpty();
        assertThat(vector.storedWrite("chunks", "c2")).isEmpty();
    }

    @Test
    void compensatesAcrossAllCapturedPayloadsWhenRetryWidensAndFails() {
        var seedEntity = entity("e1", "old");
        graph.seedEntity(seedEntity);
        relational.failNextCommitTransiently();
        var attempt = new AtomicInteger();

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            var round = attempt.incrementAndGet();
            storage.graphStore().saveEntity(entity("e1", "attempt-" + round));
            if (round == 2) {
                graph.failOnNthWrite(2);
                storage.graphStore().saveEntity(entity("e2", "new"));
                storage.graphStore().saveRelation(relation("r2", "e1", "e2", "new"));
            }
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #2");

        assertThat(relational.attempts()).isEqualTo(2);
        var payloads = graph.preImagePayloads();
        assertThat(payloads).hasSize(2);
        assertThat(graph.restoreCalls()).hasSize(2);
        assertThat(graph.restoreCalls().get(0)).isSameAs(payloads.get(0));
        assertThat(graph.restoreCalls().get(1)).isSameAs(payloads.get(1));
        assertThat(graph.storedEntity("e1")).contains(seedEntity);
        assertThat(graph.storedEntity("e2")).isEmpty();
        assertThat(graph.storedRelation("r2")).isEmpty();
    }

    @Test
    void restoresExactPreImageAfterPartialBatchApply() {
        graph.seedEntity(entity("e1", "old-1"));
        graph.seedEntity(entity("e2", "old-2"));
        graph.failOnNthWrite(3);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            for (var index = 1; index <= 5; index++) {
                storage.graphStore().saveEntity(entity("e" + index, "new-" + index));
            }
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #3");

        assertThat(graph.storedEntities()).containsExactly(entity("e1", "old-1"), entity("e2", "old-2"));
        assertThat(graph.storedEntity("e3")).isEmpty();
        assertThat(graph.storedEntity("e4")).isEmpty();
        assertThat(graph.storedEntity("e5")).isEmpty();

        var payload = graph.restoreCalls().get(0);
        graph.restorePreImage(payload);
        assertThat(graph.storedEntities()).containsExactly(entity("e1", "old-1"), entity("e2", "old-2"));
    }

    @Test
    void keepsRelationalStateWhenGraphApplyFails() {
        var seedDocument = new DocumentStore.DocumentRecord("doc-0", "seed", "seed", Map.of("seed", "true"));
        var seedChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        relational.documentStore().save(seedDocument);
        relational.chunkStore().save(seedChunk);
        graph.seedEntity(entity("e0", "seed"));
        graph.failOnNthWrite(1);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "incoming", "body", Map.of()));
            storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
            storage.graphStore().saveEntity(entity("e1", "one"));
            return null;
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("write #1");

        assertThat(relational.attempts()).isEqualTo(1);
        assertThat(relational.restoreCount()).isZero();
        assertThat(relational.documentStore().list()).containsExactly(seedDocument);
        assertThat(relational.chunkStore().list()).containsExactly(seedChunk);
        assertThat(graph.storedEntities()).containsExactly(entity("e0", "seed"));
    }

    @Test
    void reportsCompensationFailureAsSuppressed() {
        var seedEntity = entity("e0", "seed");
        var seedVector = vectorWrite("c0", "seed", 1.0d);
        graph.seedEntity(seedEntity);
        vector.seed("chunks", seedVector);
        var restoreFailure = new IllegalStateException("graph-pre-image-restore-failed");
        graph.failOnRestore(restoreFailure);
        var commitFailure = new IllegalStateException("commit-failed");
        relational.failNextCommit(commitFailure);

        assertThatThrownBy(() -> provider().writeAtomically(storage -> {
            storage.graphStore().saveEntity(entity("e1", "one"));
            enrichedSave(storage, enriched("c1", "one", 0.5d));
            return null;
        })).isSameAs(commitFailure);

        assertThat(commitFailure.getSuppressed()).containsExactly(restoreFailure);
        assertThat(vector.restoreCalls()).hasSize(1);
        assertThat(vector.storedWrites("chunks")).containsExactly(seedVector);
        assertThat(graph.storedEntity("e0")).contains(seedEntity);
        assertThat(graph.storedEntity("e1")).contains(entity("e1", "one"));
    }

    private AtomicStorageProvider provider() {
        return StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(vector)
            .build()
            .toStorageProvider();
    }

    private static void enrichedSave(AtomicStorageProvider.AtomicStorageView storage, HybridVectorStore.EnrichedVectorRecord record) {
        ((HybridVectorStore) storage.vectorStore()).saveAllEnriched("chunks", List.of(record));
    }

    private static GraphStore.EntityRecord entity(String id, String marker) {
        return new GraphStore.EntityRecord(
            id,
            "name-" + marker,
            "type-" + marker,
            "description-" + marker,
            List.of("alias-" + marker),
            List.of("chunk-" + marker)
        );
    }

    private static GraphStore.RelationRecord relation(String id, String srcId, String tgtId, String marker) {
        return new GraphStore.RelationRecord(
            id,
            srcId,
            tgtId,
            "keywords-" + marker,
            "description-" + marker,
            1.0d,
            List.of("chunk-" + marker)
        );
    }

    private static HybridVectorStore.EnrichedVectorRecord enriched(String id, String marker, double value) {
        return new HybridVectorStore.EnrichedVectorRecord(
            id,
            List.of(value, 1.0d - value),
            "text-" + marker,
            List.of("kw-" + marker)
        );
    }

    private static VectorStorageAdapter.VectorWrite vectorWrite(String id, String marker, double value) {
        return VectorStorageAdapter.VectorWrite.of(enriched(id, marker, value));
    }
}
