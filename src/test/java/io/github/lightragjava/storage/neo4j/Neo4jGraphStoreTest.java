package io.github.lightragjava.storage.neo4j;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.testcontainers.containers.Neo4jContainer;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jGraphStoreTest {
    @Test
    void smokeTestLoadsNeo4jTestDependencies() {
        assertThat(Driver.class).isNotNull();
        assertThat(Neo4jContainer.class).isNotNull();
    }
}
