package io.github.lightrag.storage.memgraph;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import io.github.lightrag.storage.neo4j.Neo4jWorkspaceGraphLifecycle;
import io.github.lightrag.support.GraphViewParity;
import io.github.lightrag.support.MemgraphTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceScopedMemgraphGraphStoreTest {
    private static final String DATABASE = "memgraph";

    @Container
    private static final GenericContainer<?> MEMGRAPH = MemgraphTestContainers.create();

    @BeforeEach
    void resetGraph() {
        try (var driver = newDriver();
             var session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            session.executeWrite(tx -> {
                tx.run("MATCH (node:Entity) DETACH DELETE node");
                return null;
            });
        }
    }

    @Test
    void bootstrapsUniqueConstraintAndIndexesForWorkspaceLookups() {
        try (var store = newStore("alpha")) {
            assertThat(store.loadEntity("missing")).isEmpty();
        }
        try (var driver = newDriver();
             var session = driver.session(SessionConfig.forDatabase(DATABASE))) {
            // Auto-commit: Memgraph rejects storage information queries inside multi-command
            // transactions.
            var constraints = session.run("SHOW CONSTRAINT INFO").list(record ->
                record.get("properties").asList(value -> value.asString())
            );
            assertThat(constraints).anySatisfy(properties -> assertThat(properties).contains("scopedId"));
            var indexes = session.run("SHOW INDEX INFO").list(record -> List.of(
                record.get("label").asString(""),
                record.get("property").asList(value -> value.asString()).toString()
            ));
            assertThat(indexes).contains(
                List.of("Entity", "[scopedId]"),
                List.of("Entity", "[workspaceId]")
            );
        }
    }

    @Test
    void savesAndLoadsEntitiesAndRelationsWithinWorkspaceOnly() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alice = entity("entity-1", "Alice");
            var bob = entity("entity-1", "Bob");
            var relation = relation("relation-1", "entity-1", "entity-2", "alpha knows placeholder");

            alpha.saveEntity(alice);
            alpha.saveRelation(relation);
            beta.saveEntity(bob);

            assertThat(alpha.loadEntity("entity-1")).contains(alice);
            assertThat(alpha.loadRelation("relation-1")).contains(relation);
            assertThat(alpha.findRelations("entity-1")).containsExactly(relation);
            assertThat(alpha.loadEntity("entity-2")).isEmpty();

            assertThat(beta.loadEntity("entity-1")).contains(bob);
            assertThat(beta.allRelations()).isEmpty();
            assertThat(beta.findRelations("entity-1")).isEmpty();
        }
    }

    @Test
    void batchEntityApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
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
    void batchRelationApisKeepOrderSkipMissingRepeatDuplicatesAndRespectWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaFirst = relation("relation-1", "entity-1", "entity-2", "alpha-v1");
            var alphaLast = relation("relation-1", "entity-3", "entity-4", "alpha-v2");
            var alphaSecond = relation("relation-2", "entity-3", "entity-1", "alpha-second");
            var betaRelation = relation("relation-1", "entity-7", "entity-8", "beta");

            alpha.saveRelations(List.of(alphaFirst, alphaSecond, alphaLast));
            beta.saveRelations(List.of(betaRelation));
            alpha.saveRelations(List.of());

            assertThat(alpha.allRelations()).containsExactly(alphaLast, alphaSecond);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
            assertThat(alpha.loadRelations(List.of("relation-2", "missing", "relation-1", "relation-2")))
                .containsExactly(alphaSecond, alphaLast, alphaSecond);
        }
    }

    @Test
    void deletesReportExactCountsAndDetachRelations() {
        try (var store = newStore("alpha")) {
            store.saveEntities(List.of(entity("e1", "Alice"), entity("e2", "Bob")));
            store.saveRelations(List.of(
                relation("r1", "e1", "e2", "first"),
                relation("r2", "e2", "e1", "second")
            ));

            assertThat(store.deleteRelations(List.of("r1"))).isEqualTo(1);
            assertThat(store.deleteRelations(List.of("r1"))).isZero();
            assertThat(store.deleteRelations(List.of("missing"))).isZero();

            assertThat(store.deleteEntities(List.of("e1"))).isEqualTo(1);
            assertThat(store.deleteEntities(List.of("e1"))).isZero();
            assertThat(store.deleteEntities(List.of("missing"))).isZero();

            assertThat(store.allEntities()).extracting(GraphStore.EntityRecord::id).containsExactly("e2");
            assertThat(store.allRelations()).isEmpty();
        }
    }

    @Test
    void searchEntitiesByTextMatchesTheCandidateFieldsWithinTheWorkspaceOnly() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            alpha.saveEntities(List.of(
                new GraphStore.EntityRecord("e1", "Alice", "person", "", List.of(), List.of()),
                new GraphStore.EntityRecord("e2", "Bob", "researcher", "colleague of Alice", List.of(), List.of()),
                new GraphStore.EntityRecord("e3", "Gamma", "person", "", List.of("ALICE-TWO"), List.of()),
                new GraphStore.EntityRecord("e4", "Delta", "artifact", "", List.of(), List.of())
            ));
            beta.saveEntity(new GraphStore.EntityRecord("e9", "Alice", "person", "", List.of(), List.of()));

            assertThat(alpha.searchEntitiesByText("alice"))
                .extracting(GraphStore.EntityRecord::id)
                .containsExactly("e1", "e2", "e3");
            assertThat(alpha.searchEntitiesByText("  ")).isEmpty();
            assertThat(alpha.searchEntitiesByText("e4")).isEmpty();
            assertThat(beta.searchEntitiesByText("gamma")).isEmpty();
        }
    }

    @Test
    void knowledgeGraphViewsMatchTheDefaultImplementation() {
        try (var store = newStore("alpha")) {
            store.saveEntities(GraphViewParity.ENTITIES);
            store.saveRelations(GraphViewParity.RELATIONS);

            GraphViewParity.assertParityWithDefaultImplementation(store);
        }
    }

    @Test
    void executesAdHocCypherWithParametersAndConvertsValues() {
        try (var store = newStore("alpha")) {
            store.saveEntity(entity("e1", "Alice"));
            store.saveRelations(List.of(relation("r1", "e1", "e2", "first")));

            var scalars = store.executeCypher(
                "MATCH (n:Entity {workspaceId: $workspaceId, id: $id}) RETURN n.name AS name, n.type AS type",
                Map.of("workspaceId", "alpha", "id", "e1"));
            assertThat(scalars.columns()).containsExactly("name", "type");
            assertThat(scalars.records()).containsExactly(Map.of("name", "Alice", "type", "person"));

            var nodes = store.executeCypher(
                "MATCH (n:Entity {workspaceId: 'alpha', id: 'e1'}) RETURN n",
                Map.of());
            assertThat(nodes.columns()).containsExactly("n");
            var node = (Map<?, ?>) nodes.records().get(0).get("n");
            assertThat(node.get("elementId")).isNotNull();
            assertThat(node.get("labels")).isEqualTo(List.of("Entity"));
            var nodeProperties = (Map<?, ?>) node.get("properties");
            assertThat(nodeProperties.get("name")).isEqualTo("Alice");
            assertThat(nodeProperties.get("workspaceId")).isEqualTo("alpha");

            var relations = store.executeCypher(
                "MATCH (:Entity {workspaceId: 'alpha'})-[r:RELATION]->() RETURN r",
                Map.of());
            assertThat(relations.columns()).containsExactly("r");
            var relation = (Map<?, ?>) relations.records().get(0).get("r");
            assertThat(relation.get("type")).isEqualTo("RELATION");
            assertThat(relation.get("startNodeElementId")).isNotNull();
            assertThat(relation.get("endNodeElementId")).isNotNull();
            var relationProperties = (Map<?, ?>) relation.get("properties");
            assertThat(relationProperties.get("relation_id")).isEqualTo("r1");
        }
    }

    @Test
    void executesMutatingCypherWithoutReturn() {
        try (var store = newStore("alpha")) {
            store.saveEntity(entity("e1", "Alice"));

            var result = store.executeCypher(
                "MATCH (n:Entity {workspaceId: 'alpha', id: 'e1'}) SET n.description = $description",
                Map.of("description", "updated by cypher"));

            assertThat(result.columns()).isEmpty();
            assertThat(result.records()).isEmpty();
            assertThat(store.loadEntity("e1").map(GraphStore.EntityRecord::description))
                .contains("updated by cypher");
        }
    }

    @Test
    void restoreReplacesOnlyItsOwnWorkspaceGraph() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaOriginal = entity("entity-1", "Alpha Original");
            var alphaReplacement = entity("entity-2", "Alpha Replacement");
            var alphaReplacementRelation = relation("relation-2", "entity-2", "entity-3", "replacement");
            var betaEntity = entity("entity-1", "Beta");
            var betaRelation = relation("relation-1", "entity-1", "entity-2", "beta relation");

            alpha.saveEntity(alphaOriginal);
            beta.saveEntity(betaEntity);
            beta.saveRelation(betaRelation);

            alpha.restore(new Neo4jGraphSnapshot(
                List.of(alphaReplacement),
                List.of(alphaReplacementRelation)
            ));

            assertThat(alpha.loadEntity("entity-1")).isEmpty();
            assertThat(alpha.loadEntity("entity-2")).contains(alphaReplacement);
            assertThat(alpha.allRelations()).containsExactly(alphaReplacementRelation);

            assertThat(beta.loadEntity("entity-1")).contains(betaEntity);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
        }
    }

    /**
     * The Neo4j lifecycle implementation is dialect-neutral Cypher plus Bolt counters, so Memgraph
     * reuses it directly; this pins that reuse down before the platform wires it in.
     */
    @Test
    void workspaceGraphDropRemovesOnlyThatWorkspaceGraph() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            alpha.saveEntity(entity("e1", "Alice"));
            beta.saveEntity(entity("e1", "Bob"));

            try (var driver = newDriver()) {
                var lifecycle = new Neo4jWorkspaceGraphLifecycle(driver, DATABASE);
                assertThat(lifecycle.dropWorkspaceGraph("alpha")).isTrue();
                assertThat(lifecycle.dropWorkspaceGraph("alpha")).isFalse();
            }

            assertThat(alpha.allEntities()).isEmpty();
            assertThat(beta.loadEntity("e1")).isPresent();
        }
    }

    private static WorkspaceScopedMemgraphGraphStore newStore(String workspaceId) {
        return new WorkspaceScopedMemgraphGraphStore(newMemgraphConfig(), new WorkspaceScope(workspaceId));
    }

    private static MemgraphGraphConfig newMemgraphConfig() {
        return new MemgraphGraphConfig(
            "bolt://" + MEMGRAPH.getHost() + ":" + MEMGRAPH.getMappedPort(7687),
            "",
            "",
            DATABASE
        );
    }

    private static Driver newDriver() {
        return GraphDatabase.driver(
            "bolt://" + MEMGRAPH.getHost() + ":" + MEMGRAPH.getMappedPort(7687),
            AuthTokens.none()
        );
    }

    private static GraphStore.EntityRecord entity(String id, String name) {
        return new GraphStore.EntityRecord(
            id,
            name,
            "person",
            name + " description",
            List.of(),
            List.of("chunk-" + id)
        );
    }

    private static GraphStore.RelationRecord relation(
        String relationId,
        String sourceEntityId,
        String targetEntityId,
        String description
    ) {
        return new GraphStore.RelationRecord(
            relationId,
            sourceEntityId,
            targetEntityId,
            "knows",
            description,
            0.9d,
            List.of("chunk-" + relationId)
        );
    }
}
