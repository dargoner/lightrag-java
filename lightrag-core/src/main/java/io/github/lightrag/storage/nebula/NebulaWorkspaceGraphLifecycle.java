package io.github.lightrag.storage.nebula;

import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.NebulaPoolConfig;
import com.vesoft.nebula.client.graph.exception.AuthFailedException;
import com.vesoft.nebula.client.graph.exception.ClientServerIncompatibleException;
import com.vesoft.nebula.client.graph.exception.IOErrorException;
import com.vesoft.nebula.client.graph.exception.InvalidConfigException;
import com.vesoft.nebula.client.graph.exception.NotValidConnectionException;
import com.vesoft.nebula.client.graph.net.Session;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.WorkspaceGraphLifecycle;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * NebulaGraph lifecycle capability for whole-workspace graphs, for maintenance paths that run
 * outside the storage adapter. A workspace's graph is not a separate NebulaGraph object: all
 * workspaces of a deployment share one space (see {@link NebulaGraphConfig}) and a workspace owns
 * the vertices and edges tagged with its workspace id (see {@link NebulaGraphStore}), so the
 * workspace id itself is the graph identifier and dropping the graph removes every vertex carrying
 * that tag.
 */
public final class NebulaWorkspaceGraphLifecycle implements WorkspaceGraphLifecycle, AutoCloseable {

    private final NebulaGraphConfig config;
    private final NebulaPool pool;

    /**
     * Opens the connection pool eagerly, closed by {@link #close()}. A direct {@link NebulaPool} is
     * used instead of a session pool because the space is not assumed to exist yet - constructing a
     * session pool binds every session with {@code USE space} and fails otherwise.
     */
    public NebulaWorkspaceGraphLifecycle(NebulaGraphConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        var createdPool = new NebulaPool();
        try {
            if (!createdPool.init(NebulaSupport.addresses(config), new NebulaPoolConfig())) {
                createdPool.close();
                throw new StorageException(
                    "NebulaGraph lifecycle cannot reach all graphd addresses " + NebulaSupport.addresses(config)
                );
            }
        } catch (UnknownHostException | InvalidConfigException exception) {
            throw new StorageException(
                "NebulaGraph lifecycle failed to initialise the connection pool",
                exception
            );
        }
        this.pool = createdPool;
    }

    /** The workspace id is the graph identifier on this backend. */
    @Override
    public String workspaceGraphId(String workspaceId) {
        return NebulaSupport.requireNonBlank(workspaceId, "workspaceId");
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
     * Removes every vertex the workspace owns; edges cascade ({@code DELETE VERTEX ... WITH EDGE}).
     * Returns {@code false} when the space or the workspace's vertices are already gone, so callers
     * can treat the operation as idempotent.
     */
    @Override
    public boolean dropWorkspaceGraph(String workspaceGraphId) {
        var workspaceId = NebulaSupport.requireNonBlank(workspaceGraphId, "workspaceGraphId");
        try {
            var session = pool.getSession(config.username(), config.password(), true);
            try {
                if (!spaceExists(session)) {
                    return false;
                }
                NebulaSupport.run(session, "use space", "USE `" + config.space() + "`;");
                var vertices = NebulaSupport.run(
                    session,
                    "drop workspace graph",
                    "LOOKUP ON entity WHERE entity.workspace_id == " + NebulaSupport.quoted(workspaceId)
                        + " YIELD id(vertex) AS vid;"
                );
                if (vertices.rowsSize() == 0) {
                    return false;
                }
                var vids = new ArrayList<String>(vertices.rowsSize());
                for (var index = 0; index < vertices.rowsSize(); index++) {
                    vids.add(NebulaSupport.string(vertices.rowValues(index).get("vid"), "vertex id"));
                }
                for (var batch : NebulaSupport.batches(vids)) {
                    var literals = batch.stream().map(NebulaSupport::quoted).collect(Collectors.joining(", "));
                    NebulaSupport.run(session, "drop workspace graph", "DELETE VERTEX " + literals + " WITH EDGE;");
                }
                return true;
            } finally {
                session.release();
            }
        } catch (NotValidConnectionException | IOErrorException | AuthFailedException
                 | ClientServerIncompatibleException exception) {
            throw new StorageException(
                "Failed to drop the NebulaGraph workspace graph '%s'".formatted(workspaceId),
                exception
            );
        }
    }

    /** {@code SHOW SPACES} reports one row per space with the name in its first column. */
    private boolean spaceExists(Session session) throws IOErrorException {
        var spaces = NebulaSupport.run(session, "show spaces", "SHOW SPACES;");
        for (var index = 0; index < spaces.rowsSize(); index++) {
            if (config.space().equals(NebulaSupport.string(spaces.rowValues(index).get(0), "space name"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        pool.close();
    }
}
