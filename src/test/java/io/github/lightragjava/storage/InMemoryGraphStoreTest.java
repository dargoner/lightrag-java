package io.github.lightragjava.storage;

import io.github.lightragjava.storage.memory.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
