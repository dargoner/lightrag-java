package io.github.lightrag.storage.memgraph;

import io.github.lightrag.storage.WorkspaceGraphLifecycle;
import io.github.lightrag.storage.neo4j.Neo4jWorkspaceGraphLifecycle;
import org.neo4j.driver.Driver;

import java.util.List;
import java.util.Objects;

/**
 * Memgraph lifecycle capability for whole-workspace graphs, for maintenance paths that run outside
 * the storage adapter. Memgraph shares the Neo4j statement shape - one shared database, every node
 * tagged with the workspace id (see {@link WorkspaceScopedMemgraphGraphStore}), no separate graph
 * object per workspace - so the operations delegate to {@link Neo4jWorkspaceGraphLifecycle} and
 * only driver ownership differs.
 */
public final class MemgraphWorkspaceGraphLifecycle implements WorkspaceGraphLifecycle, AutoCloseable {

    private final Driver driver;
    private final boolean ownsDriver;
    private final Neo4jWorkspaceGraphLifecycle delegate;

    /** Creates its own driver for the given config, closed by {@link #close()}. */
    public MemgraphWorkspaceGraphLifecycle(MemgraphGraphConfig config) {
        this(
            MemgraphSupport.createDriver(Objects.requireNonNull(config, "config")),
            config.database(),
            true
        );
    }

    /** Borrowed-driver constructor; {@link #close()} then leaves the driver untouched. */
    public MemgraphWorkspaceGraphLifecycle(Driver driver, String database) {
        this(driver, database, false);
    }

    private MemgraphWorkspaceGraphLifecycle(Driver driver, String database, boolean ownsDriver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.delegate = new Neo4jWorkspaceGraphLifecycle(driver, database);
        this.ownsDriver = ownsDriver;
    }

    /** The workspace id is the graph identifier on this backend. */
    @Override
    public String workspaceGraphId(String workspaceId) {
        return delegate.workspaceGraphId(workspaceId);
    }

    /**
     * The workspace-to-graph mapping is not textual on this backend, so no family listing exists:
     * same empty listing as {@link Neo4jWorkspaceGraphLifecycle}.
     */
    @Override
    public List<String> listWorkspaceGraphIds(String workspaceIdPrefix) {
        return delegate.listWorkspaceGraphIds(workspaceIdPrefix);
    }

    /** Removes every node the workspace owns; {@code false} when nothing was deleted. */
    @Override
    public boolean dropWorkspaceGraph(String workspaceGraphId) {
        return delegate.dropWorkspaceGraph(workspaceGraphId);
    }

    @Override
    public void close() {
        if (ownsDriver) {
            driver.close();
        }
    }
}
