package io.github.lightrag.storage.falkordb;

import com.falkordb.Driver;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.WorkspaceGraphLifecycle;

import java.util.List;
import java.util.Objects;

/**
 * FalkorDB lifecycle capability for whole-workspace graphs, for maintenance paths that run outside
 * the storage adapter: dropping the graph of a deleted workspace and listing the graphs of a
 * workspace family so orphaned projections can be reconciled. Every workspace has its own named
 * FalkorDB graph (see {@link FalkorDbSupport#graphName}), so these operations map onto
 * {@code GRAPH.DELETE} and {@code GRAPH.LIST} directly.
 */
public final class FalkorDbWorkspaceGraphLifecycle implements WorkspaceGraphLifecycle, AutoCloseable {

    private final Driver driver;
    private final boolean ownsDriver;

    /** Creates its own driver for the given config, closed by {@link #close()}. */
    public FalkorDbWorkspaceGraphLifecycle(FalkorDbGraphConfig config) {
        this(FalkorDbSupport.createDriver(Objects.requireNonNull(config, "config")), true);
    }

    /** Borrowed-driver constructor; {@link #close()} then leaves the driver untouched. */
    public FalkorDbWorkspaceGraphLifecycle(Driver driver) {
        this(driver, false);
    }

    private FalkorDbWorkspaceGraphLifecycle(Driver driver, boolean ownsDriver) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.ownsDriver = ownsDriver;
    }

    /** The FalkorDB graph name a workspace resolves to; see {@link FalkorDbSupport#graphName(String)}. */
    @Override
    public String workspaceGraphId(String workspaceId) {
        return FalkorDbSupport.graphName(Objects.requireNonNull(workspaceId, "workspaceId"));
    }

    /**
     * Names of the graphs whose sanitised name starts with the sanitised {@code workspaceIdPrefix},
     * in natural order. The prefix is matched in the same sanitised form {@link #workspaceGraphId}
     * puts on disk, so callers pass the raw workspace family prefix (for example {@code "app-kb-"}).
     */
    @Override
    public List<String> listWorkspaceGraphIds(String workspaceIdPrefix) {
        if (workspaceIdPrefix == null || workspaceIdPrefix.isBlank()) {
            throw new IllegalArgumentException("workspaceIdPrefix is required");
        }
        var sanitizedPrefix = FalkorDbSupport.sanitizeWorkspace(workspaceIdPrefix.strip());
        try {
            return driver.listGraphs().stream()
                .filter(name -> name != null && name.startsWith(sanitizedPrefix))
                .sorted()
                .toList();
        } catch (RuntimeException exception) {
            throw new StorageException(
                "Failed to list FalkorDB graphs for workspace prefix '%s'".formatted(sanitizedPrefix),
                exception
            );
        }
    }

    /**
     * Drops the named FalkorDB graph. Returns {@code false} when the graph is already gone -
     * including when it vanished between the existence check and the drop - so callers can treat
     * the operation as idempotent.
     */
    @Override
    public boolean dropWorkspaceGraph(String workspaceGraphId) {
        if (workspaceGraphId == null || workspaceGraphId.isBlank()) {
            throw new IllegalArgumentException("workspaceGraphId is required");
        }
        var graphId = workspaceGraphId.strip();
        try {
            if (!driver.listGraphs().contains(graphId)) {
                return false;
            }
            try {
                driver.graph(graphId).deleteGraph();
            } catch (RuntimeException dropFailure) {
                // A concurrent drop makes the command fail because the graph is already gone.
                if (!driver.listGraphs().contains(graphId)) {
                    return false;
                }
                throw dropFailure;
            }
            return true;
        } catch (RuntimeException exception) {
            throw new StorageException("Failed to drop the FalkorDB graph '%s'".formatted(graphId), exception);
        }
    }

    @Override
    public void close() {
        if (ownsDriver) {
            FalkorDbSupport.closeDriver(driver);
        }
    }
}
