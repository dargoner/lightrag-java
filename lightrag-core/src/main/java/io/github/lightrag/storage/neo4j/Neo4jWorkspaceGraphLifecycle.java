package io.github.lightrag.storage.neo4j;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.WorkspaceGraphLifecycle;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;

import java.util.List;
import java.util.Objects;

/**
 * Neo4j lifecycle capability for whole-workspace graphs, for maintenance paths that run outside
 * the storage adapter. A Neo4j workspace has no separate graph object: everything the workspace
 * owns lives in one shared database, tagged with the workspace id (see
 * {@link WorkspaceScopedNeo4jGraphStore}), so a workspace graph is identified by the workspace id
 * itself and dropping it removes every node carrying that tag.
 */
public final class Neo4jWorkspaceGraphLifecycle implements WorkspaceGraphLifecycle {

    private static final String ENTITY_LABEL = "Entity";

    private final Driver driver;
    private final SessionConfig sessionConfig;

    public Neo4jWorkspaceGraphLifecycle(Driver driver, String database) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.sessionConfig = SessionConfig.forDatabase(requireNonBlank(database, "database"));
    }

    /** The workspace id is the graph identifier on this backend. */
    @Override
    public String workspaceGraphId(String workspaceId) {
        return requireNonBlank(workspaceId, "workspaceId");
    }

    /**
     * The workspace-to-graph mapping is not textual on this backend, so no family listing exists:
     * returning an empty list keeps maintenance callers from guessing at destructive targets.
     */
    @Override
    public List<String> listWorkspaceGraphIds(String workspaceIdPrefix) {
        if (workspaceIdPrefix == null || workspaceIdPrefix.isBlank()) {
            throw new IllegalArgumentException("workspaceIdPrefix is required");
        }
        return List.of();
    }

    /**
     * Removes every node the workspace owns, mirroring the wholesale-replace cleanup of
     * {@link WorkspaceScopedNeo4jGraphStore#restore}. Returns {@code false} when nothing was
     * deleted, so callers can treat the operation as idempotent.
     */
    @Override
    public boolean dropWorkspaceGraph(String workspaceGraphId) {
        var workspaceId = requireNonBlank(workspaceGraphId, "workspaceGraphId");
        try (var session = driver.session(sessionConfig)) {
            var deleted = session.executeWrite(tx -> tx.run(
                """
                MATCH (node:%s {workspaceId: $workspaceId})
                DETACH DELETE node
                """.formatted(ENTITY_LABEL),
                org.neo4j.driver.Values.parameters("workspaceId", workspaceId)
            ).consume().counters().nodesDeleted());
            return deleted > 0;
        } catch (RuntimeException exception) {
            throw new StorageException(
                "Failed to drop the Neo4j workspace graph '%s'".formatted(workspaceId),
                exception
            );
        }
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }
}
