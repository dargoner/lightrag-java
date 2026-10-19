package io.github.lightrag.storage;

import io.github.lightrag.indexing.GraphVectorIndexer;
import io.github.lightrag.indexing.HybridVectorPayloads;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.lightrag.support.RelationIds.relationId;

class GraphVectorIndexerAttributeTest {
    @Test
    void recordConversionsCarryAttributesWithoutChangingVectorPayloads() {
        var store = new InMemoryGraphStore();
        store.saveEntity(new GraphStore.EntityRecord(
            "alice",
            "Alice",
            "person",
            "Researcher",
            List.of("Al"),
            List.of("doc-1:0"),
            "/a.md",
            Map.of("Age", 33L)
        ));
        store.saveRelation(new GraphStore.RelationRecord(
            relationId("alice", "bob"),
            "alice",
            "bob",
            "works_with",
            "Collaboration",
            0.8d,
            "doc-1:0",
            "/a.md",
            Map.of("since", 2020L)
        ));
        var entity = GraphVectorIndexer.toEntity(store.allEntities().get(0));
        var relation = GraphVectorIndexer.toRelation(store.allRelations().get(0));

        assertThat(entity.attributes()).containsEntry("Age", 33L);
        assertThat(relation.attributes()).containsEntry("since", 2020L);

        var entityPayload = HybridVectorPayloads.entityPayloads(
            List.of(entity),
            List.of(new VectorStore.VectorRecord("alice", List.of(1.0d, 0.0d)))
        ).get(0);
        assertThat(entityPayload.searchableText())
            .isEqualTo("Alice\nperson\nResearcher\nAl")
            .doesNotContain("Age");
        assertThat(entityPayload.metadata()).isEmpty();

        var relationPayload = HybridVectorPayloads.relationPayloads(
            List.of(relation),
            List.of(new VectorStore.VectorRecord(relation.id(), List.of(1.0d, 0.0d)))
        ).get(0);
        assertThat(relationPayload.searchableText()).doesNotContain("since");
        assertThat(relationPayload.metadata()).isEmpty();
    }
}
