package io.github.lightrag.storage.memgraph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

import java.util.Objects;

/**
 * Pure helpers for the Memgraph graph backend. No connection is opened here.
 */
final class MemgraphSupport {

    private MemgraphSupport() {
    }

    /**
     * Creates a Bolt driver for the given config. Credentials are always sent in the basic form:
     * a no-auth Memgraph instance ignores them, and {@link MemgraphGraphConfig} represents one as
     * blank username and password.
     */
    static Driver createDriver(MemgraphGraphConfig config) {
        var source = Objects.requireNonNull(config, "config");
        return GraphDatabase.driver(
            source.boltUri(),
            AuthTokens.basic(source.username(), source.password())
        );
    }
}
