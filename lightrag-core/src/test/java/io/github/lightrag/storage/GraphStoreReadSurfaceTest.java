package io.github.lightrag.storage;

import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class GraphStoreReadSurfaceTest {
    private GraphStore store;

    @BeforeEach
    void setUp() {
        var inMemory = new InMemoryGraphStore();
        inMemory.saveEntities(List.of(
            entity("alpha", "Alpha"),
            entity("beta", "Beta"),
            entity("betamax", "Betamax"),
            entity("delta", "Delta"),
            entity("epsilon", "Epsilon"),
            entity("gamma", "Gamma")
        ));
        inMemory.saveRelations(List.of(
            relation("r1", "alpha", "beta"),
            relation("r2", "alpha", "gamma"),
            relation("r3", "alpha", "delta"),
            relation("r4", "beta", "gamma")
        ));
        store = inMemory;
    }

    @Test
    void labelsAreSortedDistinctEntityIds() {
        assertThat(store.labels())
            .containsExactly("alpha", "beta", "betamax", "delta", "epsilon", "gamma");
    }

    @Test
    void searchLabelsMatchesIdAndNameCaseInsensitivelyAndHonorsTheLimit() {
        assertThat(store.searchLabels("BETA", 10)).containsExactly("beta", "betamax");
        assertThat(store.searchLabels("a", 3)).containsExactly("alpha", "beta", "betamax");
        assertThat(store.searchLabels("   ", 5)).isEmpty();
        assertThat(store.searchLabels("alpha", 0)).isEmpty();
    }

    @Test
    void searchEntitiesByTextMatchesTheFourCandidateFieldsCaseInsensitivelyInIdOrder() {
        var inMemory = new InMemoryGraphStore();
        inMemory.saveEntities(List.of(
            new GraphStore.EntityRecord("e1", "Alpha", "person", "", List.of(), List.of()),
            new GraphStore.EntityRecord("e2", "Beta", "concept", "an ALPHA note", List.of(), List.of()),
            new GraphStore.EntityRecord("e3", "Gamma", "concept", "", List.of("Alpha-Two"), List.of()),
            new GraphStore.EntityRecord("e4", "Delta", "concept", "", List.of(), List.of())
        ));

        assertThat(inMemory.searchEntitiesByText("alpha"))
            .extracting(GraphStore.EntityRecord::id)
            .containsExactly("e1", "e2", "e3");
        assertThat(inMemory.searchEntitiesByText("  ")).isEmpty();
        // Ids are not a candidate field, unlike searchLabels.
        assertThat(inMemory.searchEntitiesByText("e4")).isEmpty();
    }

    @Test
    void starReturnsTheHighestDegreeNodesAndFlagsTruncation() {
        var capped = store.getKnowledgeGraph("*", 3, 3);

        assertThat(capped.truncated()).isTrue();
        assertThat(capped.nodes()).extracting(GraphEntity::id)
            .containsExactly("alpha", "beta", "gamma");

        var full = store.getKnowledgeGraph("*", 3, 100);

        assertThat(full.truncated()).isFalse();
        assertThat(full.nodes()).extracting(GraphEntity::id)
            .containsExactly("alpha", "beta", "gamma", "delta", "betamax", "epsilon");
        assertThat(full.edges()).extracting(io.github.lightrag.api.GraphRelation::id)
            .containsExactlyInAnyOrder("r1", "r2", "r3", "r4");
        assertEdgeEndpointsAreReturned(full);
    }

    @Test
    void bfsRespectsDepthAndMaxNodesAndIncludesOnlyEdgesAmongReturnedNodes() {
        var reachable = store.getKnowledgeGraph("alpha", 1, 10);

        // The depth cut leaves neighbors of the last level unexplored - upstream counts a same-level
        // sibling that is not yet visited as unexplored, so this view is flagged truncated even though
        // the whole two-hop neighborhood fits.
        assertThat(reachable.truncated()).isTrue();
        assertThat(reachable.nodes()).extracting(GraphEntity::id)
            .containsExactly("alpha", "beta", "gamma", "delta");
        assertThat(reachable.edges()).extracting(io.github.lightrag.api.GraphRelation::id)
            .containsExactlyInAnyOrder("r1", "r2", "r3", "r4");
        assertEdgeEndpointsAreReturned(reachable);

        var closed = store.getKnowledgeGraph("alpha", 3, 10);

        assertThat(closed.truncated()).isFalse();
        assertThat(closed.nodes()).extracting(GraphEntity::id)
            .containsExactly("alpha", "beta", "gamma", "delta");

        var capped = store.getKnowledgeGraph("alpha", 1, 2);

        assertThat(capped.truncated()).isTrue();
        assertThat(capped.nodes()).extracting(GraphEntity::id).containsExactly("alpha", "beta");
        assertThat(capped.edges()).extracting(io.github.lightrag.api.GraphRelation::id)
            .containsExactly("r1");

        var depthLimited = store.getKnowledgeGraph("delta", 0, 10);

        assertThat(depthLimited.truncated()).isTrue();
        assertThat(depthLimited.nodes()).extracting(GraphEntity::id).containsExactly("delta");
        assertThat(depthLimited.edges()).isEmpty();
    }

    @Test
    void unknownLabelReturnsAnEmptyView() {
        var view = store.getKnowledgeGraph("missing", 1, 10);

        assertThat(view.nodes()).isEmpty();
        assertThat(view.edges()).isEmpty();
        assertThat(view.truncated()).isFalse();
    }

    @Test
    void degreesCountIncidentRelationsPerRequestedIdWithZeroFilledMisses() {
        assertThat(store.degrees(List.of("beta", "missing", "alpha", "alpha")))
            .containsExactly(
                entry("beta", 2),
                entry("missing", 0),
                entry("alpha", 3)
            );
        assertThat(store.degrees(List.of()))
            .isEmpty();
    }

    @Test
    void degreesCountASelfLoopOnceAndAreUnmodifiable() {
        var inMemory = new InMemoryGraphStore();
        inMemory.saveEntities(List.of(entity("a", "A"), entity("b", "B")));
        inMemory.saveRelations(List.of(relation("loop", "a", "a"), relation("ab", "a", "b")));

        var degrees = inMemory.degrees(List.of("b", "a", "ghost"));

        assertThat(degrees).containsExactly(entry("b", 1), entry("a", 2), entry("ghost", 0));
        assertThatThrownBy(() -> degrees.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void viewNodesCarryTheStoredEntityFilePaths() {
        var inMemory = new InMemoryGraphStore();
        inMemory.saveEntities(List.of(new GraphStore.EntityRecord(
            "alpha", "Alpha", "Concept", "", List.of(), List.of(), "/a.md<SEP>/b.md")));

        var view = inMemory.getKnowledgeGraph("alpha", 1, 10);

        assertThat(view.nodes()).singleElement()
            .extracting(GraphEntity::filePath)
            .isEqualTo("/a.md<SEP>/b.md");
    }

    private static void assertEdgeEndpointsAreReturned(KnowledgeGraphView view) {
        var returnedIds = view.nodes().stream().map(GraphEntity::id).toList();
        assertThat(view.edges()).allSatisfy(edge -> assertThat(returnedIds)
            .contains(edge.srcId(), edge.tgtId()));
    }

    private static GraphStore.EntityRecord entity(String id, String name) {
        return new GraphStore.EntityRecord(id, name, "Concept", "", List.of(), List.of());
    }

    private static GraphStore.RelationRecord relation(String relationId, String srcId, String tgtId) {
        return new GraphStore.RelationRecord(relationId, srcId, tgtId, "relates", "", 1.0d, "", "");
    }
}
