package io.github.lightrag.storage.falkordb;

import com.falkordb.Driver;
import com.falkordb.impl.api.DriverImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Pure helpers for the FalkorDB graph backend. No driver connection involved.
 */
final class FalkorDbSupport {
    /** Graph namespace used by the single Java knowledge graph (upstream {@code namespace.py:20}). */
    static final String GRAPH_NAMESPACE = "chunk_entity_relation";

    private static final Logger log = LoggerFactory.getLogger(FalkorDbSupport.class);

    private FalkorDbSupport() {
    }

    /**
     * Derives the FalkorDB graph name for a workspace with the same scheme the Apache AGE backend
     * uses in {@code PostgresAgeSupport.graphName}: the default workspace keeps the bare namespace,
     * any other workspace is prefixed after sanitising everything outside {@code [A-Za-z0-9_]}.
     * FalkorDB graph names are Redis keys without a length limit, so the name is not clipped.
     */
    static String graphName(String workspaceId) {
        var workspace = workspaceId == null ? "" : workspaceId.strip();
        return workspace.isEmpty() || workspace.equalsIgnoreCase("default")
            ? GRAPH_NAMESPACE
            : sanitizeWorkspace(workspace) + "_" + GRAPH_NAMESPACE;
    }

    /**
     * Maps every character outside {@code [A-Za-z0-9_]} to {@code _}, the same replacement
     * {@link #graphName} applies to workspace prefixes. Case is preserved, so the result
     * {@code startsWith} comparisons observe the same textual form as the stored graph names.
     */
    static String sanitizeWorkspace(String workspace) {
        return workspace.replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Creates a driver for the given config; a blank username selects the unauthenticated form. */
    static Driver createDriver(FalkorDbGraphConfig config) {
        return config.username().isBlank()
            ? new DriverImpl(config.host(), config.port())
            : new DriverImpl(config.host(), config.port(), config.username(), config.password());
    }

    /**
     * Closes the driver. {@link Driver} inherits {@code Closeable.close()}, which declares
     * {@link IOException}, but the Jedis-pool close behind it has no checked failure path.
     */
    static void closeDriver(Driver driver) {
        try {
            driver.close();
        } catch (IOException exception) {
            log.debug("Could not close the FalkorDB driver", exception);
        }
    }
}
