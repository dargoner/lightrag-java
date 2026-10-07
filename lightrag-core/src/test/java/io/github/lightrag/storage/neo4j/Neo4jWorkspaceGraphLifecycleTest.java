package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.support.Neo4jTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class Neo4jWorkspaceGraphLifecycleTest {
    @Container
    private static final Neo4jContainer<?> NEO4J = Neo4jTestContainers.create();

    @BeforeEach
    void resetGraph() {
        try (var driver = newDriver();
             var session = driver.session(SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run("MATCH (node) DETACH DELETE node");
                return null;
            });
        }
    }

    @Test
    void identifiesAWorkspaceGraphByTheWorkspaceId() {
        try (var driver = newDriver()) {
            var lifecycle = new Neo4jWorkspaceGraphLifecycle(driver, "neo4j");

            assertThat(lifecycle.workspaceGraphId("alpha-kb-1")).isEqualTo("alpha-kb-1");
        }
    }

    @Test
    void listsNothingBecauseTheMappingIsNotTextual() {
        try (var driver = newDriver()) {
            var lifecycle = new Neo4jWorkspaceGraphLifecycle(driver, "neo4j");

            assertThat(lifecycle.listWorkspaceGraphIds("app-kb-")).isEmpty();
            assertThatThrownBy(() -> lifecycle.listWorkspaceGraphIds("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceIdPrefix");
        }
    }

    @Test
    void dropsOnlyTheRequestedWorkspaceAndReportsIdempotently() {
        var alphaWorkspace = "alpha-" + suffix();
        var betaWorkspace = "beta-" + suffix();
        try (var driver = newDriver()) {
            var lifecycle = new Neo4jWorkspaceGraphLifecycle(driver, "neo4j");
            try (var alpha = newStore(driver, alphaWorkspace);
                 var beta = newStore(driver, betaWorkspace)) {
                alpha.saveEntity(entity("entity-1", "Alice"));
                beta.saveEntity(entity("entity-1", "Bob"));
            }

            assertThat(lifecycle.dropWorkspaceGraph(alphaWorkspace)).isTrue();

            try (var alphaAfter = newStore(driver, alphaWorkspace);
                 var betaAfter = newStore(driver, betaWorkspace)) {
                assertThat(alphaAfter.loadEntity("entity-1")).isEmpty();
                assertThat(betaAfter.loadEntity("entity-1")).isPresent();
            }

            assertThat(lifecycle.dropWorkspaceGraph(alphaWorkspace)).isFalse();
        }
    }

    @Test
    void rejectsBlankArguments() {
        try (var driver = newDriver()) {
            var lifecycle = new Neo4jWorkspaceGraphLifecycle(driver, "neo4j");

            assertThatThrownBy(() -> lifecycle.workspaceGraphId(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
            assertThatThrownBy(() -> lifecycle.dropWorkspaceGraph(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceGraphId");
        }
    }

    private static WorkspaceScopedNeo4jGraphStore newStore(Driver driver, String workspaceId) {
        return new WorkspaceScopedNeo4jGraphStore(driver, "neo4j", new WorkspaceScope(workspaceId));
    }

    private static EntityRecord entity(String id, String name) {
        return new EntityRecord(
            id,
            name,
            "person",
            name + " description",
            List.of(),
            List.of("chunk-" + id)
        );
    }

    private static Driver newDriver() {
        return GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
