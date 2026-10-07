package io.github.lightrag.storage.falkordb;

import com.falkordb.Driver;
import com.falkordb.impl.api.DriverImpl;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import io.github.lightrag.support.FalkorDbTestContainers;
import io.github.lightrag.support.GraphViewParity;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class FalkorDbGraphStoreTest {
    private static final int FALKORDB_PORT = 6379;

    @Container
    private static final GenericContainer<?> FALKORDB = FalkorDbTestContainers.create();

    @Test
    void createsTheEntityIdIndexOnceAndReplaysTheBootstrapSafely() {
        var workspace = newWorkspaceId();

        try (var store = newStore(workspace)) {
            store.saveEntity(entity("entity-1", "Alice"));

            // Replaying the bootstrap (a second store over the same graph) must be a no-op.
            try (var replay = newStore(workspace)) {
                assertThat(replay.loadEntity("entity-1")).isPresent();
            }
        }

        var indexes = withDriver(driver -> driver.graph(FalkorDbSupport.graphName(workspace)).query(
            "CALL db.indexes() YIELD label, properties, entitytype, status "
                + "WHERE entitytype = 'NODE' AND label = 'base' "
                + "RETURN properties, status"
        ));
        assertThat(indexes.size()).isEqualTo(1);
        var row = indexes.iterator().next();
        assertThat(strings(row.getValue("properties"))).contains("entity_id");
        assertThat(row.getString("status")).isEqualTo("OPERATIONAL");
    }

    @Test
    void roundTripsEntitiesAndRelationsWithSpecialCharacters() {
        try (var store = newStore()) {
            var alice = new EntityRecord(
                "entity-\"1\"\\🚀",
                "Alice \"Liddell\"",
                "person",
                "Line1\nLine2 中文 🎩 $AGE1$",
                List.of("A", "阿丽丝"),
                List.of("chunk-1", "chunk-2"),
                "/docs/alice.md<SEP>/docs/bob.md"
            );
            var bob = new EntityRecord("entity-2", "Bob", "person", "", List.of(), List.of());
            var relation = new RelationRecord(
                "relation-1",
                alice.id(),
                bob.id(),
                "knows",
                "Alice knows Bob\n(中文备注)",
                0.9d,
                "chunk-1<SEP>chunk-2",
                "/docs/alice.md"
            );

            store.saveEntity(alice);
            store.saveEntity(bob);
            store.saveRelation(relation);

            assertThat(store.loadEntity(alice.id())).contains(alice);
            assertThat(store.loadEntity(bob.id())).contains(bob);
            assertThat(store.loadRelation("relation-1")).contains(relation);
        }
    }

    @Test
    void upsertingAnEntityReplacesStoredPropertiesWithoutDuplicates() {
        try (var store = newStore()) {
            store.saveEntity(new EntityRecord("entity-1", "Alice", "person", "old", List.of("A"), List.of("chunk-1")));
            var updated = new EntityRecord(
                "entity-1",
                "Alice Smith",
                "researcher",
                "new",
                List.of("A", "Alice"),
                List.of("chunk-2")
            );

            store.saveEntity(updated);

            assertThat(store.loadEntity("entity-1")).contains(updated);
            assertThat(store.allEntities()).containsExactly(updated);
            assertThat(store.labels()).containsExactly("entity-1");
        }
    }

    @Test
    void upsertingARelationReplacesTheEdgeBetweenTheSameEndpoints() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2");
            store.saveRelation(new RelationRecord("relation-1", "e1", "e2", "knows", "first", 0.9d, List.of("chunk-1")));
            var updated = new RelationRecord("relation-1", "e1", "e2", "knows", "second", 0.4d, List.of("chunk-2"));

            store.saveRelation(updated);

            assertThat(store.allRelations()).containsExactly(updated);
            assertThat(store.loadRelation("relation-1")).contains(updated);
            assertThat(store.findRelations("e1")).containsExactly(updated);

            // Upstream keeps at most one edge per endpoint pair: a save whose endpoints match an
            // existing edge deletes that edge even when the incoming relation id differs.
            var samePairDifferentId = new RelationRecord("relation-2", "e2", "e1", "knows", "reverse", 0.7d, List.of("chunk-3"));

            store.saveRelation(samePairDifferentId);

            assertThat(store.allRelations()).containsExactly(samePairDifferentId);
            assertThat(store.loadRelation("relation-1")).isEmpty();
            assertThat(store.findRelations("e1")).containsExactly(samePairDifferentId);
        }
    }

    @Test
    void selfLoopsAreStoredAndFoundExactlyOnce() {
        try (var store = newStore()) {
            saveEntities(store, "e1");
            var selfLoop = new RelationRecord("relation-self", "e1", "e1", "knows", "self", 0.5d, List.of("chunk-1"));

            store.saveRelation(selfLoop);

            assertThat(store.findRelations("e1")).containsExactly(selfLoop);
            assertThat(store.allRelations()).containsExactly(selfLoop);
            assertThat(store.loadRelation("relation-self")).contains(selfLoop);

            // Re-saving a self-loop replaces it instead of doubling it.
            store.saveRelation(selfLoop);

            assertThat(store.allRelations()).containsExactly(selfLoop);
            assertThat(store.deleteRelations(List.of("relation-self"))).isEqualTo(1);
        }
    }

    @Test
    void rejectsRelationsWhoseEndpointsAreMissingAndLeavesTheGraphUntouched() {
        try (var store = newStore()) {
            saveEntities(store, "present");

            var relation = new RelationRecord("relation-1", "present", "missing", "knows", "d", 0.5d, List.of("chunk-1"));

            assertThatThrownBy(() -> store.saveRelation(relation))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("missing endpoint(s) [missing]")
                .hasMessageContaining("FalkorDB reports no error");

            assertThat(store.allRelations()).isEmpty();
            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("present");
        }
    }

    @Test
    void findsRelationsAcrossIncomingAndOutgoingEdges() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            var first = new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1"));
            var second = new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1"));
            store.saveRelation(first);
            store.saveRelation(second);

            assertThat(store.findRelations("e2")).containsExactly(first, second);
            assertThat(store.findRelations("e1")).containsExactly(first);
            assertThat(store.findRelations("e3")).containsExactly(second);
            assertThat(store.findRelations("ghost")).isEmpty();
        }
    }

    @Test
    void deletingEntitiesCascadesEdgesAndCountsOnlyRealDeletes() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1")));

            assertThat(store.deleteEntities(List.of("e2", "ghost"))).isEqualTo(1);

            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("e1", "e3");
            assertThat(store.allRelations()).isEmpty();
        }
    }

    @Test
    void deletingRelationsCountsOnlyRealDeletes() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-b", "e2", "e3", "knows", "b", 0.5d, List.of("chunk-1")));

            assertThat(store.deleteRelations(List.of("relation-a", "ghost"))).isEqualTo(1);

            assertThat(store.allRelations()).extracting(RelationRecord::id).containsExactly("relation-b");
            assertThat(store.allEntities()).hasSize(3);
            assertThat(store.deleteEntities(List.of())).isZero();
            assertThat(store.deleteRelations(List.of())).isZero();
        }
    }

    @Test
    void listsEntitiesRelationsAndLabelsInDeterministicOrder() {
        try (var store = newStore()) {
            saveEntities(store, "zeta", "alpha", "mid");
            store.saveRelation(new RelationRecord("relation-b", "alpha", "zeta", "knows", "b", 0.5d, List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-a", "alpha", "mid", "knows", "a", 0.5d, List.of("chunk-1")));

            assertThat(store.allEntities()).extracting(EntityRecord::id).containsExactly("alpha", "mid", "zeta");
            assertThat(store.allRelations()).extracting(RelationRecord::id).containsExactly("relation-a", "relation-b");
            assertThat(store.labels()).containsExactly("alpha", "mid", "zeta");
        }
    }

    @Test
    void batchEntityApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore();
             var beta = newStore()) {
            var alphaFirst = entity("entity-1", "Alice-v1");
            var alphaLast = entity("entity-1", "Alice-v2");
            var alphaSecond = entity("entity-2", "Adam");
            var betaEntity = entity("entity-1", "Bob");

            alpha.saveEntities(List.of(alphaFirst, alphaSecond, alphaLast));
            beta.saveEntities(List.of(betaEntity));
            alpha.saveEntities(List.of());

            assertThat(alpha.allEntities()).containsExactly(alphaLast, alphaSecond);
            assertThat(beta.allEntities()).containsExactly(betaEntity);
            assertThat(alpha.loadEntities(List.of("entity-2", "missing", "entity-1", "entity-2")))
                .containsExactly(alphaSecond, alphaLast, alphaSecond);
        }
    }

    @Test
    void batchRelationApisKeepOrderSkipMissingRepeatDuplicates() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2", "e3", "e4");
            var first = new RelationRecord("relation-1", "e1", "e2", "knows", "first", 0.9d, List.of("chunk-1"));
            var updated = new RelationRecord("relation-1", "e1", "e2", "knows", "updated", 0.4d, List.of("chunk-2"));
            var second = new RelationRecord("relation-2", "e3", "e4", "knows", "second", 0.5d, List.of("chunk-3"));

            store.saveRelations(List.of(first, second, updated));
            store.saveRelations(List.of());

            assertThat(store.allRelations()).containsExactly(updated, second);
            assertThat(store.loadRelations(List.of("relation-2", "missing", "relation-1", "relation-2")))
                .containsExactly(second, updated, second);
            assertThat(store.loadRelation("relation-1")).contains(updated);
        }
    }

    @Test
    void searchEntitiesByTextMatchesTheCandidateFieldsWithinTheWorkspaceOnly() {
        try (var alpha = newStore();
             var beta = newStore()) {
            alpha.saveEntities(List.of(
                new EntityRecord("e1", "Alice", "person", "", List.of(), List.of()),
                new EntityRecord("e2", "Bob", "researcher", "colleague of Alice", List.of(), List.of()),
                new EntityRecord("e3", "Gamma", "person", "", List.of("ALICE-TWO", "共事"), List.of()),
                new EntityRecord("e4", "Delta", "artifact", "", List.of(), List.of())
            ));
            beta.saveEntity(new EntityRecord("e9", "Alice", "person", "", List.of(), List.of()));

            assertThat(alpha.searchEntitiesByText("alice"))
                .extracting(EntityRecord::id)
                .containsExactly("e1", "e2", "e3");
            assertThat(alpha.searchEntitiesByText("共事"))
                .extracting(EntityRecord::id)
                .containsExactly("e3");
            assertThat(alpha.searchEntitiesByText("  ")).isEmpty();
            assertThat(alpha.searchEntitiesByText("e4")).isEmpty();
            assertThat(beta.searchEntitiesByText("gamma")).isEmpty();
        }
    }

    @Test
    void clearRemovesAllVerticesAndEdges() {
        try (var store = newStore()) {
            saveEntities(store, "e1", "e2");
            store.saveRelation(new RelationRecord("relation-a", "e1", "e2", "knows", "a", 0.5d, List.of("chunk-1")));

            store.clear();

            assertThat(store.allEntities()).isEmpty();
            assertThat(store.allRelations()).isEmpty();
            assertThat(store.labels()).isEmpty();
        }
    }

    @Test
    void isolatesWorkspacesInSeparateGraphs() {
        try (var first = newStore("alpha");
             var second = newStore("beta")) {
            first.saveEntity(new EntityRecord("entity-1", "Alice", "person", "first", List.of(), List.of()));
            second.saveEntity(new EntityRecord("entity-1", "Bob", "person", "second", List.of(), List.of()));

            assertThat(FalkorDbSupport.graphName("alpha")).isNotEqualTo(FalkorDbSupport.graphName("beta"));
            assertThat(first.loadEntity("entity-1").map(EntityRecord::name)).contains("Alice");
            assertThat(second.loadEntity("entity-1").map(EntityRecord::name)).contains("Bob");
        }
    }

    @Test
    void supportsMixedCaseWorkspaceGraphNames() {
        var workspace = "MixedCase" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var graphName = workspace + "_chunk_entity_relation";

        try (var store = newStore(workspace)) {
            assertThat(FalkorDbSupport.graphName(workspace)).isEqualTo(graphName);

            store.saveEntity(new EntityRecord("entity-1", "Alice", "person", "d", List.of(), List.of("chunk-1")));

            assertThat(store.loadEntity("entity-1")).isPresent();
            assertThat(store.labels()).containsExactly("entity-1");
        }

        assertThat(withDriver(Driver::listGraphs)).contains(graphName);
    }

    @Test
    void dropsAndListsWorkspaceGraphsWithoutTouchingOthers() {
        var family = "fam" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var firstWorkspace = family + "_one";
        var secondWorkspace = family + "_two";
        var outsiderWorkspace = newWorkspaceId();

        try (var first = newStore(firstWorkspace);
             var second = newStore(secondWorkspace);
             var outsider = newStore(outsiderWorkspace)) {
            first.saveEntity(entity("e1", "Alice"));
            second.saveEntity(entity("e1", "Bob"));
            outsider.saveEntity(entity("e1", "Carol"));
        }

        try (var lifecycle = new FalkorDbWorkspaceGraphLifecycle(newConfig())) {
            var firstGraph = lifecycle.workspaceGraphId(firstWorkspace);
            var secondGraph = lifecycle.workspaceGraphId(secondWorkspace);
            assertThat(firstGraph).isEqualTo(FalkorDbSupport.graphName(firstWorkspace));

            assertThat(lifecycle.listWorkspaceGraphIds(family)).containsExactly(firstGraph, secondGraph);

            assertThat(lifecycle.dropWorkspaceGraph(firstGraph)).isTrue();
            assertThat(lifecycle.dropWorkspaceGraph(firstGraph)).isFalse();
        }

        try (var first = newStore(firstWorkspace);
             var second = newStore(secondWorkspace);
             var outsider = newStore(outsiderWorkspace)) {
            assertThat(first.allEntities()).isEmpty();
            assertThat(second.loadEntity("e1")).isPresent();
            assertThat(outsider.loadEntity("e1")).isPresent();
        }
    }

    @Test
    void loadsMissingRecordsAsEmpty() {
        try (var store = newStore()) {
            assertThat(store.loadEntity("ghost")).isEmpty();
            assertThat(store.loadRelation("ghost")).isEmpty();
        }
    }

    @Test
    void loadsLegacyVerticesWithoutFilePathPropertyAsEmpty() {
        var workspace = newWorkspaceId();
        withDriver(driver -> {
            driver.graph(FalkorDbSupport.graphName(workspace)).query(
                "CREATE (:base {entity_id: 'legacy-1', name: 'Legacy', entity_type: 'person', "
                    + "description: 'written before file_path existed', aliases: [], source_id: 'chunk-1'})"
            );
            return null;
        });

        try (var store = newStore(workspace)) {
            assertThat(store.loadEntity("legacy-1")).get()
                .extracting(EntityRecord::filePath)
                .isEqualTo("");
        }
    }

    @Test
    void knowledgeGraphViewsMatchTheDefaultImplementationOnTheFalkorDbBackend() {
        try (var store = newStore()) {
            store.saveEntities(GraphViewParity.ENTITIES);
            store.saveRelations(GraphViewParity.ENDPOINT_COMPLETE_RELATIONS);

            GraphViewParity.assertParityOnEndpointCompleteGraph(store);
        }
    }

    @Test
    void executesAdHocCypherWithParametersAndConvertsValues() {
        try (var store = newStore()) {
            store.saveEntity(new EntityRecord("e1", "Alice", "person", "orig", List.of("A"), List.of("chunk-1")));
            store.saveEntity(new EntityRecord("e2", "Bob", "researcher", "other", List.of(), List.of("chunk-1")));
            store.saveRelation(new RelationRecord("relation-1", "e1", "e2", "knows", "linked", 0.8d, List.of("chunk-1")));

            var scalars = store.executeCypher(
                "MATCH (n:base {entity_id: $id}) RETURN n.name AS name, n.entity_type AS type",
                Map.of("id", "e1"));
            assertThat(scalars.columns()).containsExactly("name", "type");
            assertThat(scalars.records()).containsExactly(Map.of("name", "Alice", "type", "person"));

            var ordered = store.executeCypher(
                "MATCH (n:base) RETURN n.entity_id ORDER BY n.entity_id DESC LIMIT 5",
                Map.of());
            assertThat(ordered.columns()).containsExactly("n.entity_id");
            assertThat(ordered.records()).extracting(record -> record.get("n.entity_id"))
                .containsExactly("e2", "e1");

            var vertices = store.executeCypher("MATCH (n:base {entity_id: 'e1'}) RETURN n", Map.of());
            assertThat(vertices.columns()).containsExactly("n");
            var vertex = (Map<?, ?>) vertices.records().get(0).get("n");
            assertThat(vertex.get("labels")).isEqualTo(List.of("base"));
            var vertexProperties = (Map<?, ?>) vertex.get("properties");
            assertThat(vertexProperties.get("entity_id")).isEqualTo("e1");
            assertThat(vertexProperties.get("name")).isEqualTo("Alice");

            var edges = store.executeCypher("MATCH ()-[r:DIRECTED]->() RETURN r", Map.of());
            assertThat(edges.columns()).containsExactly("r");
            var edge = (Map<?, ?>) edges.records().get(0).get("r");
            assertThat(edge.get("type")).isEqualTo("DIRECTED");
            assertThat(edge.get("source")).isInstanceOf(Number.class);
            assertThat(edge.get("destination")).isInstanceOf(Number.class);
            var edgeProperties = (Map<?, ?>) edge.get("properties");
            assertThat(edgeProperties.get("relation_id")).isEqualTo("relation-1");
            assertThat(edgeProperties.get("src_id")).isEqualTo("e1");
            assertThat(edgeProperties.get("tgt_id")).isEqualTo("e2");
        }
    }

    @Test
    void executesMutatingCypherWithoutReturn() {
        try (var store = newStore()) {
            store.saveEntity(new EntityRecord("e1", "Alice", "person", "orig", List.of(), List.of("chunk-1")));

            var result = store.executeCypher(
                "MATCH (n:base {entity_id: $id}) SET n.description = $description",
                Map.of("id", "e1", "description", "updated by cypher"));

            assertThat(result.columns()).isEmpty();
            assertThat(result.records()).isEmpty();
            assertThat(store.loadEntity("e1").map(EntityRecord::description)).contains("updated by cypher");
        }
    }

    private static void saveEntities(FalkorDbGraphStore store, String... ids) {
        for (var id : ids) {
            store.saveEntity(new EntityRecord(
                id,
                id.toUpperCase(Locale.ROOT),
                "type",
                "description of " + id,
                List.of(),
                List.of("chunk-1")
            ));
        }
    }

    private static EntityRecord entity(String id, String name) {
        return new EntityRecord(id, name, "person", name + " description", List.of(), List.of("chunk-" + id));
    }

    private static String newWorkspaceId() {
        return "ws_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static FalkorDbGraphStore newStore() {
        return newStore(newWorkspaceId());
    }

    private static FalkorDbGraphStore newStore(String workspaceId) {
        return new FalkorDbGraphStore(newConfig(), workspaceId);
    }

    private static FalkorDbGraphConfig newConfig() {
        return new FalkorDbGraphConfig(FALKORDB.getHost(), FALKORDB.getMappedPort(FALKORDB_PORT), "", "");
    }

    private static Driver newDriver() {
        return new DriverImpl(FALKORDB.getHost(), FALKORDB.getMappedPort(FALKORDB_PORT));
    }

    private static <T> T withDriver(Function<Driver, T> work) {
        var driver = newDriver();
        try {
            return work.apply(driver);
        } finally {
            FalkorDbSupport.closeDriver(driver);
        }
    }

    private static List<String> strings(Object value) {
        var values = new ArrayList<String>();
        if (value instanceof Iterable<?> iterable) {
            for (var element : iterable) {
                values.add(String.valueOf(element));
            }
        } else if (value != null) {
            values.add(String.valueOf(value));
        }
        return List.copyOf(values);
    }
}
