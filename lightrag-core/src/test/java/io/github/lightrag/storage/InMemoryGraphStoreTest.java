package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryGraphStoreTest {
    @Test
    void graphStoreReturnsOneHopRelationsForEntity() {
        var store = new InMemoryGraphStore();
        var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
        var bob = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of("B"), List.of("chunk-1"));
        var carol = new GraphStore.EntityRecord("entity-3", "Carol", "person", "Manager", List.of("C"), List.of("chunk-2"));
        var knows = new GraphStore.RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "Alice knows Bob",
            0.9d,
            List.of("chunk-1")
        );
        var manages = new GraphStore.RelationRecord(
            "relation-2",
            "entity-2",
            "entity-3",
            "manages",
            "Bob manages Carol",
            0.8d,
            List.of("chunk-2")
        );

        store.saveEntity(carol);
        store.saveEntity(bob);
        store.saveEntity(alice);
        store.saveRelation(manages);
        store.saveRelation(knows);

        assertThat(store.loadEntity("entity-2")).contains(bob);
        assertThat(store.loadRelation("relation-1")).contains(knows);
        assertThat(store.allEntities()).containsExactlyInAnyOrder(alice, bob, carol);
        assertThat(store.allRelations()).containsExactlyInAnyOrder(knows, manages);
        assertThat(store.findRelations("entity-2")).containsExactly(knows, manages);
    }

    @Test
    void defaultBatchOperationsHonorSequentialSemantics() {
        var store = new InMemoryGraphStore();

        var entityInitial = new GraphStore.EntityRecord(
            "entity-1",
            "Alice v1",
            "person",
            "Initial version",
            List.of("A"),
            List.of("chunk-1")
        );
        var entityUpdated = new GraphStore.EntityRecord(
            "entity-1",
            "Alice v2",
            "person",
            "Updated version",
            List.of("A", "A2"),
            List.of("chunk-2")
        );
        var entitySecond = new GraphStore.EntityRecord(
            "entity-2",
            "Bob",
            "person",
            "Second entity",
            List.of("B"),
            List.of("chunk-3")
        );

        store.saveEntities(List.of(entityInitial, entitySecond, entityUpdated));

        assertThat(store.loadEntity("entity-1")).contains(entityUpdated);

        var loadedEntities = store.loadEntities(List.of("entity-1", "missing", "entity-2", "entity-1"));
        assertThat(loadedEntities).containsExactly(entityUpdated, entitySecond, entityUpdated);

        var relationInitial = new GraphStore.RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "mentors",
            "Alice mentors Bob",
            0.5d,
            List.of("chunk-1")
        );
        var relationUpdated = new GraphStore.RelationRecord(
            "relation-1",
            "entity-2",
            "entity-3",
            "manages",
            "Bob now manages Carol",
            0.8d,
            List.of("chunk-4")
        );
        var relationExtra = new GraphStore.RelationRecord(
            "relation-2",
            "entity-3",
            "entity-4",
            "reports_to",
            "Carol reports to Dave",
            0.7d,
            List.of("chunk-5")
        );

        store.saveRelations(List.of(relationInitial, relationExtra, relationUpdated));

        assertThat(store.loadRelation("relation-1")).contains(relationUpdated);

        var loadedRelations = store.loadRelations(List.of("relation-1", "missing", "relation-1", "relation-2"));
        assertThat(loadedRelations).containsExactly(relationUpdated, relationUpdated, relationExtra);
    }

    @Test
    void overwritingRelationReindexesEndpoints() {
        var store = new InMemoryGraphStore();
        var original = new GraphStore.RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "Alice knows Bob",
            0.9d,
            List.of("chunk-1")
        );
        var replacement = new GraphStore.RelationRecord(
            "relation-1",
            "entity-3",
            "entity-4",
            "reports_to",
            "Carol reports to Dave",
            0.7d,
            List.of("chunk-2")
        );

        store.saveRelation(original);
        store.saveRelation(replacement);

        assertThat(store.loadRelation("relation-1")).contains(replacement);
        assertThat(store.findRelations("entity-1")).isEmpty();
        assertThat(store.findRelations("entity-2")).isEmpty();
        assertThat(store.findRelations("entity-3")).containsExactly(replacement);
        assertThat(store.findRelations("entity-4")).containsExactly(replacement);
    }

    @Test
    void readersNeverObserveRelationDetachedFromQueriedEntityDuringOverwrite() throws Exception {
        var store = new InMemoryGraphStore();
        var original = new GraphStore.RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "Alice knows Bob",
            0.9d,
            List.of("chunk-1")
        );
        var replacement = new GraphStore.RelationRecord(
            "relation-1",
            "entity-3",
            "entity-4",
            "reports_to",
            "Carol reports to Dave",
            0.7d,
            List.of("chunk-2")
        );
        var entitiesToQuery = List.of("entity-1", "entity-2", "entity-3", "entity-4");
        var start = new CountDownLatch(1);
        var writerDone = new AtomicBoolean(false);
        var failure = new AtomicReference<AssertionError>();

        store.saveRelation(original);

        var writer = new Thread(() -> {
            await(start, failure);
            for (int iteration = 0; iteration < 250_000 && failure.get() == null; iteration++) {
                store.saveRelation((iteration & 1) == 0 ? replacement : original);
            }
            writerDone.set(true);
        });

        var reader = new Thread(() -> {
            await(start, failure);
            while (!writerDone.get() && failure.get() == null) {
                for (var entityId : entitiesToQuery) {
                    for (var relation : store.findRelations(entityId)) {
                        if (!isAdjacent(entityId, relation)) {
                            failure.compareAndSet(
                                null,
                                new AssertionError("Observed stale adjacency for " + entityId + ": " + relation)
                            );
                            return;
                        }
                    }
                }
            }
        });

        writer.start();
        reader.start();
        start.countDown();
        writer.join();
        reader.join();

        assertThat(failure.get()).isNull();
    }

    private static boolean isAdjacent(String entityId, GraphStore.RelationRecord relation) {
        return relation.srcId().equals(entityId) || relation.tgtId().equals(entityId);
    }

    private static void await(CountDownLatch latch, AtomicReference<AssertionError> failure) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure.compareAndSet(null, new AssertionError("Test interrupted", exception));
        }
    }
}
