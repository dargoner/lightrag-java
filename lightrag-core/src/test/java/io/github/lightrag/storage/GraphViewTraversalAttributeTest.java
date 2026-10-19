package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryGraphStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.lightrag.support.RelationIds.relationId;

class GraphViewTraversalAttributeTest {
    @Test
    void knowledgeGraphViewCarriesEntityAndRelationAttributes() {
        var store = new InMemoryGraphStore();
        var entityAttributes = Map.<String, Object>of("Age", 33L, "tags", List.of("researcher"));
        var relationAttributes = Map.<String, Object>of("since", 2020L);
        store.saveEntity(new GraphStore.EntityRecord(
            "alice",
            "Alice",
            "person",
            "Researcher",
            List.of("Al"),
            List.of("doc-1:0"),
            "/a.md",
            entityAttributes
        ));
        store.saveEntity(new GraphStore.EntityRecord(
            "bob",
            "Bob",
            "person",
            "Engineer",
            List.of(),
            List.of("doc-1:0")
        ));
        store.saveRelation(new GraphStore.RelationRecord(
            relationId("alice", "bob"),
            "alice",
            "bob",
            "works_with",
            "Collaboration",
            0.8d,
            "doc-1:0",
            "",
            relationAttributes
        ));

        var view = store.getKnowledgeGraph("alice", 1, 10);

        assertThat(view.nodes())
            .filteredOn(node -> node.id().equals("alice"))
            .singleElement()
            .satisfies(node -> assertThat(node.attributes()).isEqualTo(entityAttributes));
        assertThat(view.edges()).singleElement()
            .satisfies(edge -> assertThat(edge.attributes()).isEqualTo(relationAttributes));
    }

    @Test
    void directTraversalSupportPreservesAttributes() {
        var entityAttributes = Map.<String, Object>of("Age", 33L);
        var relationAttributes = Map.<String, Object>of("since", 2020L);
        var support = GraphViewTraversal.inMemory(
            List.of(new GraphStore.EntityRecord(
                "alice",
                "Alice",
                "person",
                "Researcher",
                List.of(),
                List.of("doc-1:0"),
                "",
                entityAttributes
            )),
            List.of(new GraphStore.RelationRecord(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "Collaboration",
                0.8d,
                "doc-1:0",
                "",
                relationAttributes
            ))
        );

        var nodes = support.entities(List.of("alice"));

        assertThat(nodes.get("alice").attributes()).isEqualTo(entityAttributes);
        assertThat(support.relationsWithin(java.util.Set.of("alice", "bob"))).singleElement()
            .satisfies(relation -> assertThat(relation.attributes()).isEqualTo(relationAttributes));
    }
}
