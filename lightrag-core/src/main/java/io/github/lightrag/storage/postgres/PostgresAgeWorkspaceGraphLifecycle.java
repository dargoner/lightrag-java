package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.WorkspaceGraphLifecycle;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Apache AGE lifecycle capability for whole-workspace graphs, for maintenance paths that run
 * outside the storage adapter: dropping the graph of a deleted workspace and listing the graphs of
 * a workspace family so orphaned projections can be reconciled. The AGE graph is a projection of
 * the workspace's relational graph rows (see {@link PostgresAgeGraphStorageAdapter}), so these
 * operations only remove that projection.
 */
public final class PostgresAgeWorkspaceGraphLifecycle implements WorkspaceGraphLifecycle {

    private static final String LIST_GRAPHS_SQL = "SELECT name::text FROM ag_catalog.ag_graph ORDER BY name::text";

    private static final String GRAPH_EXISTS_SQL =
        "SELECT 1 FROM ag_catalog.ag_graph WHERE name = left(?::text, "
            + PostgresAgeSupport.PG_NAME_MAX_BYTES + ")::name";

    /** {@code drop_graph(..., cascade => true)} removes the graph's schema and every object in it. */
    private static final String DROP_GRAPH_SQL = "SELECT ag_catalog.drop_graph(?::name, true)";

    private final DataSource dataSource;

    public PostgresAgeWorkspaceGraphLifecycle(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** The AGE graph a workspace resolves to; see {@link PostgresAgeSupport#graphName(String)}. */
    @Override
    public String workspaceGraphId(String workspaceId) {
        return PostgresAgeSupport.graphName(workspaceId);
    }

    /**
     * Names of the graphs whose sanitised name starts with the sanitised {@code workspaceIdPrefix},
     * in catalog order. The prefix is matched in the same sanitised form {@link #workspaceGraphId}
     * puts on disk, so callers pass the raw workspace family prefix (for example {@code "app-kb-"}).
     */
    @Override
    public List<String> listWorkspaceGraphIds(String workspaceIdPrefix) {
        if (workspaceIdPrefix == null || workspaceIdPrefix.isBlank()) {
            throw new IllegalArgumentException("workspaceIdPrefix is required");
        }
        var sanitizedPrefix = PostgresAgeSupport.sanitizeWorkspace(workspaceIdPrefix.strip());
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(LIST_GRAPHS_SQL);
             var resultSet = statement.executeQuery()) {
            var names = new ArrayList<String>();
            while (resultSet.next()) {
                var name = resultSet.getString(1);
                if (name != null && name.startsWith(sanitizedPrefix)) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } catch (SQLException exception) {
            throw new StorageException(
                "Failed to list Apache AGE graphs for workspace prefix '%s'".formatted(sanitizedPrefix),
                exception
            );
        }
    }

    /**
     * Drops the named AGE graph with cascade. Returns {@code false} when the graph is already gone
     * - including when it vanished between the existence check and the drop - so callers can treat
     * the operation as idempotent.
     */
    @Override
    public boolean dropWorkspaceGraph(String workspaceGraphId) {
        if (workspaceGraphId == null || workspaceGraphId.isBlank()) {
            throw new IllegalArgumentException("graphName is required");
        }
        try (var connection = dataSource.getConnection()) {
            if (!graphExists(connection, workspaceGraphId)) {
                return false;
            }
            try (var statement = connection.prepareStatement(DROP_GRAPH_SQL)) {
                statement.setString(1, workspaceGraphId);
                statement.execute();
            } catch (SQLException dropFailure) {
                // A concurrent drop makes the statement fail because the graph is already gone.
                if (!graphExists(connection, workspaceGraphId)) {
                    return false;
                }
                throw dropFailure;
            }
            return true;
        } catch (SQLException exception) {
            throw new StorageException(
                "Failed to drop the Apache AGE graph '%s'".formatted(workspaceGraphId),
                exception
            );
        }
    }

    private static boolean graphExists(Connection connection, String graphName) throws SQLException {
        try (var statement = connection.prepareStatement(GRAPH_EXISTS_SQL)) {
            statement.setString(1, graphName);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
