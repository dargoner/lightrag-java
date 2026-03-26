package io.github.lightragjava.storage.neo4j;

import io.github.lightragjava.api.WorkspaceScope;
import io.github.lightragjava.storage.GraphStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkspaceScopedNeo4jGraphStoreTest {
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
    void savesAndLoadsEntityWithinWorkspaceOnly() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alice = entity("entity-1", "Alice");
            var bob = entity("entity-1", "Bob");

            alpha.saveEntity(alice);
            beta.saveEntity(bob);

            assertThat(alpha.loadEntity("entity-1")).contains(alice);
            assertThat(beta.loadEntity("entity-1")).contains(bob);
            assertThat(alpha.allEntities()).containsExactly(alice);
            assertThat(beta.allEntities()).containsExactly(bob);
        }
    }

    @Test
    void savesRelationWithWorkspaceScopedPlaceholders() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var relation = relation("relation-1", "entity-1", "entity-2", "alpha knows placeholder");
            var betaEntity = entity("entity-1", "Bob");

            alpha.saveRelation(relation);
            beta.saveEntity(betaEntity);

            assertThat(alpha.findRelations("entity-1")).containsExactly(relation);
            assertThat(beta.findRelations("entity-1")).isEmpty();
            assertThat(beta.loadEntity("entity-1")).contains(betaEntity);
        }
    }

    @Test
    void listsEntitiesRelationsAndNeighborsWithinCurrentWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaEntity = entity("entity-1", "Alice");
            var alphaRelation = relation("relation-1", "entity-1", "entity-2", "Alice knows Adam");
            var betaEntity = entity("entity-1", "Bob");
            var betaRelation = relation("relation-1", "entity-1", "entity-3", "Bob knows Ben");

            alpha.saveEntity(alphaEntity);
            alpha.saveRelation(alphaRelation);
            beta.saveEntity(betaEntity);
            beta.saveRelation(betaRelation);

            assertThat(alpha.allEntities()).containsExactly(alphaEntity);
            assertThat(alpha.allRelations()).containsExactly(alphaRelation);
            assertThat(alpha.findRelations("entity-1")).containsExactly(alphaRelation);

            assertThat(beta.allEntities()).containsExactly(betaEntity);
            assertThat(beta.allRelations()).containsExactly(betaRelation);
            assertThat(beta.findRelations("entity-1")).containsExactly(betaRelation);
        }
    }

    @Test
    void restoreOnlyReplacesCurrentWorkspace() {
        try (var alpha = newStore("alpha");
             var beta = newStore("beta")) {
            var alphaOriginal = entity("entity-1", "Alice");
            var alphaReplacement = entity("entity-2", "Carol");
            var alphaReplacementRelation = relation("relation-2", "entity-2", "entity-4", "Carol reports to Dave");
            var betaEntity = entity("entity-1", "Bob");
            var betaRelation = relation("relation-1", "entity-1", "entity-3", "Bob knows Ben");

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

    private static WorkspaceScopedNeo4jGraphStore newStore(String workspaceId) {
        return new WorkspaceScopedNeo4jGraphStore(newNeo4jConfig(), new WorkspaceScope(workspaceId));
    }

    private static Neo4jGraphConfig newNeo4jConfig() {
        return new Neo4jGraphConfig(
            NEO4J.getBoltUrl(),
            "neo4j",
            NEO4J.getAdminPassword(),
            "neo4j"
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
