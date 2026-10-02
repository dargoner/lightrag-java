package io.github.lightrag.storage.postgres;

import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link GraphStorageAdapter} that projects the workspace's knowledge graph into an Apache AGE graph
 * on the provider's PostgreSQL data source. Created by {@link PostgresMilvusNeo4jStorageProvider}
 * when {@link PostgresGraphBackend#AGE} replaces the Neo4j projection; the relational graph rows
 * stay the durable source of truth, exactly as they are for the Neo4j projection.
 */
public final class PostgresAgeGraphStorageAdapter implements GraphStorageAdapter {
    private final DataSource dataSource;
    private final String workspaceId;
    private final PostgresAgeGraphStore graphStore;

    public PostgresAgeGraphStorageAdapter(DataSource dataSource, String workspaceId) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        this.graphStore = new PostgresAgeGraphStore(dataSource, workspaceId);
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public GraphSnapshot captureSnapshot() {
        return new GraphSnapshot(graphStore.allEntities(), graphStore.allRelations());
    }

    @Override
    public void apply(StagedGraphWrites writes) {
        var source = Objects.requireNonNull(writes, "writes");
        // Entities first: the store fails an edge write whose endpoints are absent, so entities must land first.
        if (!source.entities().isEmpty()) {
            graphStore.saveEntities(source.entities());
        }
        if (!source.relations().isEmpty()) {
            graphStore.saveRelations(source.relations());
        }
    }

    @Override
    public void restore(GraphSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        inTransaction(store -> {
            store.clear();
            store.saveEntities(source.entities());
            store.saveRelations(source.relations());
            return null;
        });
    }

    @Override
    public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        var requestedEntityIds = List.copyOf(entityIds);
        var requestedRelationIds = List.copyOf(relationIds);
        return inTransaction(store -> Optional.of(new ScopedPreImage(
            requestedEntityIds,
            store.loadEntities(requestedEntityIds),
            requestedRelationIds,
            store.loadRelations(requestedRelationIds)
        )));
    }

    /**
     * Entities are restored before relations because {@code deleteEntities} issues a DETACH DELETE: an absent
     * entity takes any relation attached to it down with it, and the relation pass re-writes the pre-image
     * relations afterwards.
     */
    @Override
    public void restorePreImage(PreImage preImage) {
        if (!(preImage instanceof ScopedPreImage scoped)) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        inTransaction(store -> {
            var presentEntityIds = scoped.entities().stream()
                .map(GraphStore.EntityRecord::id)
                .collect(Collectors.toSet());
            var absentEntityIds = scoped.entityIds().stream()
                .filter(id -> !presentEntityIds.contains(id))
                .toList();
            if (!absentEntityIds.isEmpty()) {
                store.deleteEntities(absentEntityIds);
            }
            if (!scoped.entities().isEmpty()) {
                store.saveEntities(scoped.entities());
            }
            var presentRelationIds = scoped.relations().stream()
                .map(GraphStore.RelationRecord::id)
                .collect(Collectors.toSet());
            var absentRelationIds = scoped.relationIds().stream()
                .filter(id -> !presentRelationIds.contains(id))
                .toList();
            if (!absentRelationIds.isEmpty()) {
                store.deleteRelations(absentRelationIds);
            }
            if (!scoped.relations().isEmpty()) {
                store.saveRelations(scoped.relations());
            }
            return null;
        });
    }

    @Override
    public void close() {
        // The store borrows pooled connections per operation and holds no resources of its own.
    }

    /**
     * Runs the work on a connection-bound store so every statement shares one transaction and one
     * {@code SET LOCAL search_path} session; the data-source-backed store commits per call instead.
     */
    private <T> T inTransaction(Function<PostgresAgeGraphStore, T> work) {
        Connection connection = null;
        Throwable primaryFailure = null;
        try {
            connection = dataSource.getConnection();
            var originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                var store = new PostgresAgeGraphStore(JdbcConnectionAccess.forConnection(connection), workspaceId);
                var result = work.apply(store);
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                rollback(connection, failure);
                throw failure;
            } catch (SQLException exception) {
                primaryFailure = exception;
                rollback(connection, exception);
                throw new StorageException("Apache AGE projection transaction failed", exception);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(exception);
                    } else {
                        throw new StorageException("Failed to restore Apache AGE connection state", exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to configure Apache AGE transaction", exception);
        } finally {
            closeQuietly(connection);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A failed close discards the pooled connection.
        }
    }

    private record ScopedPreImage(
        List<String> entityIds,
        List<GraphStore.EntityRecord> entities,
        List<String> relationIds,
        List<GraphStore.RelationRecord> relations
    ) implements PreImage {
    }
}
