package io.github.lightrag.storage.falkordb;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import io.github.lightrag.support.FalkorDbTestContainers;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FalkorDbGraphStorageAdapterTest {
    private static final int FALKORDB_PORT = 6379;

    @Container
    private static final GenericContainer<?> FALKORDB = FalkorDbTestContainers.create();

    @Test
    void appliesStagedWritesAndCapturesSnapshot() {
        try (var adapter = newAdapter()) {
            adapter.apply(new GraphStorageAdapter.StagedGraphWrites(
                List.of(alice(), bob()),
                List.of(aliceKnowsBob())
            ));

            var snapshot = adapter.captureSnapshot();
            assertThat(snapshot.entities()).containsExactlyInAnyOrder(alice(), bob());
            assertThat(snapshot.relations()).containsExactly(aliceKnowsBob());
            assertThat(adapter.graphStore().loadEntity(alice().id())).contains(alice());
            assertThat(adapter.graphStore().loadRelation(aliceKnowsBob().relationId()))
                .contains(aliceKnowsBob());
        }
    }

    @Test
    void rejectsRelationsWhoseEndpointsAreMissing() {
        try (var adapter = newAdapter()) {
            assertThatThrownBy(() -> adapter.apply(
                new GraphStorageAdapter.StagedGraphWrites(List.of(), List.of(aliceKnowsBob()))
            ))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("missing endpoint");

            assertThat(adapter.captureSnapshot().relations()).isEmpty();
        }
    }

    @Test
    void restoresPreImageForPresentAndAbsentIds() {
        try (var adapter = newAdapter()) {
            adapter.apply(new GraphStorageAdapter.StagedGraphWrites(
                List.of(alice(), bob()),
                List.of(aliceKnowsBob())
            ));
            var preImage = adapter.capturePreImage(
                List.of(alice().id(), carol().id()),
                List.of(aliceKnowsBob().relationId(), "relation-missing")
            ).orElseThrow();

            adapter.apply(new GraphStorageAdapter.StagedGraphWrites(
                List.of(updatedAlice(), carol()),
                List.of(carolKnowsBob())
            ));
            assertThat(adapter.graphStore().loadEntity(carol().id())).contains(carol());
            assertThat(adapter.graphStore().loadEntity(alice().id())).contains(updatedAlice());

            adapter.restorePreImage(preImage);

            var snapshot = adapter.captureSnapshot();
            assertThat(snapshot.entities()).containsExactlyInAnyOrder(alice(), bob());
            assertThat(snapshot.relations()).containsExactly(aliceKnowsBob());
        }
    }

    @Test
    void restoreReplacesTheWholeGraph() {
        try (var adapter = newAdapter()) {
            adapter.apply(new GraphStorageAdapter.StagedGraphWrites(
                List.of(alice(), bob()),
                List.of(aliceKnowsBob())
            ));

            adapter.restore(new GraphStorageAdapter.GraphSnapshot(List.of(carol()), List.of()));

            var snapshot = adapter.captureSnapshot();
            assertThat(snapshot.entities()).containsExactly(carol());
            assertThat(snapshot.relations()).isEmpty();
        }
    }

    @Test
    void rejectsForeignPreImagePayloads() {
        try (var adapter = newAdapter()) {
            assertThatThrownBy(() -> adapter.restorePreImage(new GraphStorageAdapter.PreImage() {
            }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected pre-image payload");
        }
    }

    private static EntityRecord alice() {
        return new EntityRecord(
            "entity-alice",
            "Alice",
            "person",
            "Alice description",
            List.of("Al"),
            List.of("chunk-1")
        );
    }

    private static EntityRecord updatedAlice() {
        return new EntityRecord(
            "entity-alice",
            "Alice Smith",
            "researcher",
            "updated description",
            List.of("Al", "Alice"),
            List.of("chunk-2")
        );
    }

    private static EntityRecord bob() {
        return new EntityRecord("entity-bob", "Bob", "person", "Bob description", List.of(), List.of("chunk-1"));
    }

    private static EntityRecord carol() {
        return new EntityRecord("entity-carol", "Carol", "person", "Carol description", List.of(), List.of("chunk-3"));
    }

    private static RelationRecord aliceKnowsBob() {
        return new RelationRecord(
            "relation-alice-bob",
            "entity-alice",
            "entity-bob",
            "knows",
            "Alice knows Bob",
            0.75d,
            "chunk-1<SEP>chunk-2",
            "/docs/alice.md"
        );
    }

    private static RelationRecord carolKnowsBob() {
        return new RelationRecord(
            "relation-carol-bob",
            "entity-carol",
            "entity-bob",
            "knows",
            "Carol knows Bob",
            0.5d,
            "chunk-3<SEP>chunk-4",
            "/docs/carol.md"
        );
    }

    private static FalkorDbGraphStorageAdapter newAdapter() {
        return new FalkorDbGraphStorageAdapter(
            new FalkorDbGraphConfig(FALKORDB.getHost(), FALKORDB.getMappedPort(FALKORDB_PORT), "", ""),
            "ws_" + UUID.randomUUID().toString().replace("-", "")
        );
    }
}
