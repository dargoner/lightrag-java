package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.MutableGraphStore;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

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
        if (!source.entities().isEmpty()) {
            projection.saveEntities(source.entities());
        }
        if (!source.relations().isEmpty()) {
            projection.saveRelations(source.relations());
        }
    }

    @Override
    public void restore(GraphSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        projection.restore(new Neo4jGraphSnapshot(source.entities(), source.relations()));
    }

    @Override
    public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        var requestedEntityIds = List.copyOf(entityIds);
        var requestedRelationIds = List.copyOf(relationIds);
        return Optional.of(new ScopedPreImage(
            requestedEntityIds,
            projection.loadEntities(requestedEntityIds),
            requestedRelationIds,
            projection.loadRelations(requestedRelationIds)
        ));
    }

    /**
     * Entities are restored before relations because {@code deleteEntities} issues a DETACH DELETE: an absent entity
     * takes any relation attached to it down with it, and the relation pass re-writes the pre-image relations
     * afterwards. A relation that disappears this way can only be one that was also absent from the pre-image (a
     * relation present in the pre-image had both endpoints present before the write), so the order never drops
     * pre-image state.
     */
    @Override
    public void restorePreImage(PreImage preImage) {
        if (!(preImage instanceof ScopedPreImage scoped)) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
        var presentEntityIds = scoped.entities().stream()
            .map(GraphStore.EntityRecord::id)
            .collect(Collectors.toSet());
        var absentEntityIds = scoped.entityIds().stream()
            .filter(id -> !presentEntityIds.contains(id))
            .toList();
        if (!absentEntityIds.isEmpty()) {
            projection.deleteEntities(absentEntityIds);
        }
        if (!scoped.entities().isEmpty()) {
            projection.saveEntities(scoped.entities());
        }
        var presentRelationIds = scoped.relations().stream()
            .map(GraphStore.RelationRecord::id)
            .collect(Collectors.toSet());
        var absentRelationIds = scoped.relationIds().stream()
            .filter(id -> !presentRelationIds.contains(id))
            .toList();
        if (!absentRelationIds.isEmpty()) {
            projection.deleteRelations(absentRelationIds);
        }
        if (!scoped.relations().isEmpty()) {
            projection.saveRelations(scoped.relations());
        }
    }

    @Override
    public void close() {
        projection.close();
    }

    private record ScopedPreImage(
        List<String> entityIds,
        List<GraphStore.EntityRecord> entities,
        List<String> relationIds,
        List<GraphStore.RelationRecord> relations
    ) implements PreImage {
    }

    public interface Projection extends MutableGraphStore, AutoCloseable {
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
        public void saveEntities(java.util.List<EntityRecord> entities) {
            delegate.saveEntities(entities);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            delegate.saveRelation(relation);
        }

        @Override
        public void saveRelations(java.util.List<RelationRecord> relations) {
            delegate.saveRelations(relations);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return delegate.loadEntity(entityId);
        }

        @Override
        public java.util.List<EntityRecord> loadEntities(java.util.List<String> entityIds) {
            return delegate.loadEntities(entityIds);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return delegate.loadRelation(relationId);
        }

        @Override
        public java.util.List<RelationRecord> loadRelations(java.util.List<String> relationIds) {
            return delegate.loadRelations(relationIds);
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
        public java.util.Map<String, java.util.List<RelationRecord>> findRelations(java.util.List<String> entityIds) {
            return delegate.findRelations(entityIds);
        }

        @Override
        public KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) {
            return delegate.getKnowledgeGraph(nodeLabel, maxDepth, maxNodes);
        }

        @Override
        public int deleteEntities(java.util.List<String> entityIds) {
            return delegate.deleteEntities(entityIds);
        }

        @Override
        public int deleteRelations(java.util.List<String> relationIds) {
            return delegate.deleteRelations(relationIds);
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
