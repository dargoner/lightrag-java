package io.github.lightrag.support;

import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.GraphRelation;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.memory.InMemoryGraphStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture and parity harness for {@code getKnowledgeGraph}: a chain with a ghost tail, a
 * star, a cycle with a self-loop, and an isolated node, checked over boundary parameters against
 * the interface default implementation (the normative behavior the overrides must match).
 */
public final class GraphViewParity {
    private GraphViewParity() {
    }

    public static final List<GraphStore.EntityRecord> ENTITIES = List.of(
        entity("c1", "Chain 1", "/docs/chain.md"),
        entity("c2", "Chain 2", "/docs/chain.md"),
        entity("c3", "Chain 3", "/docs/chain.md"),
        entity("c4", "Chain 4", "/docs/chain.md"),
        entity("c5", "Chain 5", "/docs/chain.md"),
        entity("h", "Hub", "/docs/star.md"),
        entity("s1", "Star 1", "/docs/star.md"),
        entity("s2", "Star 2", "/docs/star.md"),
        entity("s3", "Star 3", "/docs/star.md"),
        entity("s4", "Star 4", "/docs/star.md"),
        entity("y1", "Cycle 1", "/docs/cycle.md"),
        entity("y2", "Cycle 2", "/docs/cycle.md"),
        entity("y3", "Cycle 3", "/docs/cycle.md"),
        entity("iso", "Isolated", "/docs/iso.md")
    );

    public static final List<GraphStore.RelationRecord> RELATIONS = List.of(
        relation("r-chain-1", "c1", "c2"),
        relation("r-chain-2", "c2", "c3"),
        relation("r-chain-3", "c3", "c4"),
        relation("r-chain-4", "c4", "c5"),
        relation("r-cycle-1", "y1", "y2"),
        relation("r-cycle-2", "y2", "y3"),
        relation("r-cycle-3", "y3", "y1"),
        relation("r-ghost-1", "c5", "g1"),
        relation("r-ghost-2", "g1", "g2"),
        relation("r-self", "y1", "y1"),
        relation("r-star-1", "h", "s1"),
        relation("r-star-2", "h", "s2"),
        relation("r-star-3", "h", "s3"),
        relation("r-star-4", "h", "s4")
    );

    /**
     * Relations minus the two ghost-endpoint ones, for backends that reject relations whose
     * endpoints were never stored (AGE).
     */
    public static final List<GraphStore.RelationRecord> ENDPOINT_COMPLETE_RELATIONS = RELATIONS.stream()
        .filter(relation -> !relation.id().startsWith("r-ghost"))
        .toList();

    public record GraphViewCase(String label, int maxDepth, int maxNodes) {
    }

    public static final List<GraphViewCase> CASES = List.of(
        new GraphViewCase("*", 3, 5),
        new GraphViewCase("*", 3, 100),
        new GraphViewCase("c3", 1, 100),
        new GraphViewCase("c3", 2, 100),
        new GraphViewCase("c3", 3, 100),
        new GraphViewCase("c3", 4, 100),
        new GraphViewCase("h", 1, 3),
        new GraphViewCase("y1", 2, 100),
        new GraphViewCase("iso", 2, 10),
        new GraphViewCase("missing", 3, 10)
    );

    public static void assertParityWithDefaultImplementation(GraphStore store) {
        assertReferenceAnchors(store);
        assertParity(store, RELATIONS);
    }

    /** Same cases over an endpoint-complete graph; for backends that reject ghost relations. */
    public static void assertParityOnEndpointCompleteGraph(GraphStore store) {
        assertParity(store, ENDPOINT_COMPLETE_RELATIONS);
    }

    private static void assertParity(GraphStore store, List<GraphStore.RelationRecord> relations) {
        var baseline = new InMemoryGraphStore();
        baseline.saveEntities(ENTITIES);
        baseline.saveRelations(relations);
        for (var testCase : CASES) {
            assertThat(store.getKnowledgeGraph(testCase.label(), testCase.maxDepth(), testCase.maxNodes()))
                .as("view for %s", testCase)
                .isEqualTo(baseline.getKnowledgeGraph(testCase.label(), testCase.maxDepth(), testCase.maxNodes()));
        }
    }

    private static void assertReferenceAnchors(GraphStore store) {
        var starTruncated = store.getKnowledgeGraph("*", 3, 5);
        assertThat(starTruncated.truncated()).isTrue();
        assertThat(starTruncated.nodes()).extracting(GraphEntity::id)
            .containsExactly("h", "y1", "c2", "c3", "c4");
        assertThat(starTruncated.edges()).extracting(GraphRelation::id)
            .containsExactly("r-chain-2", "r-chain-3", "r-self");

        var fullGraph = store.getKnowledgeGraph("*", 3, 100);
        assertThat(fullGraph.truncated()).isFalse();
        assertThat(fullGraph.nodes()).extracting(GraphEntity::id).containsExactly(
            "h", "y1", "c2", "c3", "c4", "c5", "y2", "y3", "c1", "s1", "s2", "s3", "s4", "iso"
        );
        assertThat(fullGraph.edges()).extracting(GraphRelation::id).containsExactly(
            "r-chain-1", "r-chain-2", "r-chain-3", "r-chain-4",
            "r-cycle-1", "r-cycle-2", "r-cycle-3", "r-self",
            "r-star-1", "r-star-2", "r-star-3", "r-star-4"
        );
        assertThat(fullGraph.nodes()).filteredOn(node -> node.id().equals("c5"))
            .singleElement()
            .extracting(GraphEntity::filePath)
            .isEqualTo("/docs/chain.md");

        var depthLimited = store.getKnowledgeGraph("c3", 1, 100);
        assertThat(depthLimited.truncated()).isTrue();
        assertThat(depthLimited.nodes()).extracting(GraphEntity::id)
            .containsExactly("c3", "c2", "c4");
        assertThat(depthLimited.edges()).extracting(GraphRelation::id)
            .containsExactly("r-chain-2", "r-chain-3");

        var ghostView = store.getKnowledgeGraph("c3", 3, 100);
        assertThat(ghostView.truncated()).isTrue();
        assertThat(ghostView.nodes()).extracting(GraphEntity::id)
            .containsExactly("c3", "c2", "c4", "c5", "c1");
        assertThat(ghostView.edges()).extracting(GraphRelation::id)
            .containsExactly("r-chain-1", "r-chain-2", "r-chain-3", "r-chain-4", "r-ghost-1");

        var midLevelCut = store.getKnowledgeGraph("h", 1, 3);
        assertThat(midLevelCut.truncated()).isTrue();
        assertThat(midLevelCut.nodes()).extracting(GraphEntity::id)
            .containsExactly("h", "s1", "s2");
        assertThat(midLevelCut.edges()).extracting(GraphRelation::id)
            .containsExactly("r-star-1", "r-star-2");

        var isolated = store.getKnowledgeGraph("iso", 2, 10);
        assertThat(isolated.truncated()).isFalse();
        assertThat(isolated.nodes()).extracting(GraphEntity::id).containsExactly("iso");
        assertThat(isolated.edges()).isEmpty();

        var unknown = store.getKnowledgeGraph("missing", 3, 10);
        assertThat(unknown.truncated()).isFalse();
        assertThat(unknown.nodes()).isEmpty();
        assertThat(unknown.edges()).isEmpty();
    }

    private static GraphStore.EntityRecord entity(String id, String name, String filePath) {
        return new GraphStore.EntityRecord(
            id,
            name,
            "Concept",
            name + " description",
            List.of(name + "-alias"),
            List.of("chunk-" + id),
            filePath
        );
    }

    private static GraphStore.RelationRecord relation(String relationId, String srcId, String tgtId) {
        return new GraphStore.RelationRecord(
            relationId,
            srcId,
            tgtId,
            "relates",
            relationId + " description",
            1.0d,
            "chunk-" + srcId,
            "/docs/rel.md"
        );
    }
}
