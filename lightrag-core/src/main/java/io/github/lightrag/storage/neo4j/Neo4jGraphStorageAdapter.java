package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;

import java.util.Objects;

public final class Neo4jGraphStorageAdapter implements GraphStorageAdapter {
    private final Projection projection;

    public Neo4jGraphStorageAdapter(Neo4jGraphConfig config, WorkspaceScope workspaceScope) {
        this(new WorkspaceStoreProjection(new WorkspaceScopedNeo4jGraphStore(
            Objects.requireNonNull(config, "config"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        )));
    }

    public Neo4jGraphStorageAdapter(WorkspaceScopedNeo4jGraphStore store) {
        this(new WorkspaceStoreProjection(Objects.requireNonNull(store, "store")));
    }

    public Neo4jGraphStorageAdapter(Projection projection) {
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    @Override
    public GraphStore graphStore() {
        return projection;
    }

    @Override
    public GraphSnapshot captureSnapshot() {
        var snapshot = projection.captureSnapshot();
        return new GraphSnapshot(snapshot.entities(), snapshot.relations());
    }

    @Override
    public void apply(StagedGraphWrites writes) {
        var source = Objects.requireNonNull(writes, "writes");
        for (var entity : source.entities()) {
            projection.saveEntity(entity);
        }
        for (var relation : source.relations()) {
            projection.saveRelation(relation);
        }
    }

    @Override
    public void restore(GraphSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        projection.restore(new Neo4jGraphSnapshot(source.entities(), source.relations()));
    }

    @Override
    public void close() {
        projection.close();
    }

    public interface Projection extends GraphStore, AutoCloseable {
        Neo4jGraphSnapshot captureSnapshot();

        void restore(Neo4jGraphSnapshot snapshot);

        @Override
        void close();
    }

    private static final class WorkspaceStoreProjection implements Projection {
        private final WorkspaceScopedNeo4jGraphStore delegate;

        private WorkspaceStoreProjection(WorkspaceScopedNeo4jGraphStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            delegate.saveEntity(entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            delegate.saveRelation(relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return delegate.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return delegate.loadRelation(relationId);
        }

        @Override
        public java.util.List<EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public java.util.List<RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public java.util.List<RelationRecord> findRelations(String entityId) {
            return delegate.findRelations(entityId);
        }

        @Override
        public Neo4jGraphSnapshot captureSnapshot() {
            return delegate.captureSnapshot();
        }

        @Override
        public void restore(Neo4jGraphSnapshot snapshot) {
            delegate.restore(snapshot);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
