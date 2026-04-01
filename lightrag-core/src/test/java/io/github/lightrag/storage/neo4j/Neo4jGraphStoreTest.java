package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.Neo4jContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Neo4jGraphStoreTest {
    @Container
    private static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
        .withAdminPassword("password");

    @BeforeEach
    void resetGraph() {
        try (var driver = GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
             var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run("MATCH (node) DETACH DELETE node");
                return null;
            });
        }
    }

    @Test
    void savesAndLoadsEntitiesAndRelations() {
        try (var store = newGraphStore()) {
            var entity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A", "Alice A."),
                List.of("chunk-1", "chunk-2")
            );
            var relation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1", "chunk-2")
            );

            store.saveEntity(entity);
            store.saveRelation(relation);

            assertThat(store.loadEntity("entity-1")).contains(entity);
            assertThat(store.loadRelation("relation-1")).contains(relation);
        }
    }

    @Test
    void listsEntitiesAndRelationsInDeterministicOrder() {
        try (var store = newGraphStore()) {
            var secondEntity = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of("chunk-2"));
            var firstEntity = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
            var secondRelation = new GraphStore.RelationRecord(
                "relation-2",
                "entity-2",
                "entity-3",
                "manages",
                "Bob manages Carol",
                0.8d,
                List.of("chunk-2")
            );
            var firstRelation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );

            store.saveEntity(secondEntity);
            store.saveEntity(firstEntity);
            store.saveRelation(secondRelation);
            store.saveRelation(firstRelation);

            assertThat(store.allEntities()).containsExactly(firstEntity, secondEntity);
            assertThat(store.allRelations()).containsExactly(firstRelation, secondRelation);
        }
    }

    @Test
    void findsOneHopRelationsForEntity() {
        try (var store = newGraphStore()) {
            var first = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );
            var second = new GraphStore.RelationRecord(
                "relation-2",
                "entity-2",
                "entity-3",
                "manages",
                "Bob manages Carol",
                0.8d,
                List.of("chunk-2")
            );

            store.saveRelation(second);
            store.saveRelation(first);

            assertThat(store.findRelations("entity-2")).containsExactly(first, second);
        }
    }

    @Test
    void restoresGraphSnapshot() {
        try (var store = newGraphStore()) {
            var originalEntity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A"),
                List.of("chunk-1")
            );
            var originalRelation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );
            var replacementEntity = new GraphStore.EntityRecord(
                "entity-3",
                "Carol",
                "person",
                "Manager",
                List.of("C"),
                List.of("chunk-3")
            );
            var replacementRelation = new GraphStore.RelationRecord(
                "relation-2",
                "entity-3",
                "entity-4",
                "reports_to",
                "Carol reports to Dave",
                0.7d,
                List.of("chunk-3")
            );

            store.saveEntity(originalEntity);
            store.saveRelation(originalRelation);
            var snapshot = store.captureSnapshot();

            store.saveEntity(replacementEntity);
            store.saveRelation(replacementRelation);

            store.restore(snapshot);

            assertThat(store.allEntities()).containsExactly(originalEntity);
            assertThat(store.allRelations()).containsExactly(originalRelation);
        }
    }

    @Test
    void usesDefaultWorkspaceAndIsolatesFromOtherWorkspaces() {
        try (var defaultWorkspaceStore = newGraphStore();
             var alphaWorkspaceStore = new WorkspaceScopedNeo4jGraphStore(newNeo4jConfig(), new WorkspaceScope("alpha"))) {
            var defaultEntity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of(),
                List.of("chunk-default")
            );
            var alphaEntity = new GraphStore.EntityRecord(
                "entity-1",
                "Bob",
                "person",
                "Engineer",
                List.of(),
                List.of("chunk-alpha")
            );

            defaultWorkspaceStore.saveEntity(defaultEntity);
            alphaWorkspaceStore.saveEntity(alphaEntity);

            assertThat(defaultWorkspaceStore.loadEntity("entity-1")).contains(defaultEntity);
            assertThat(alphaWorkspaceStore.loadEntity("entity-1")).contains(alphaEntity);
        }
    }

    private static Neo4jGraphStore newGraphStore() {
        return new Neo4jGraphStore(newNeo4jConfig());
    }

    private static Neo4jGraphConfig newNeo4jConfig() {
        return new Neo4jGraphConfig(
            NEO4J.getBoltUrl(),
            "neo4j",
            NEO4J.getAdminPassword(),
            "neo4j"
        );
    }
}
