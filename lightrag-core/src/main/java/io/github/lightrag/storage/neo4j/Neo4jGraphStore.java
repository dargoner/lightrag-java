package io.github.lightrag.storage.neo4j;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Neo4jGraphStore implements GraphStore, AutoCloseable {
    private static final WorkspaceScope DEFAULT_SCOPE = new WorkspaceScope("default");

    private final WorkspaceScopedNeo4jGraphStore delegate;

    public Neo4jGraphStore(Neo4jGraphConfig config) {
        this.delegate = new WorkspaceScopedNeo4jGraphStore(
            Objects.requireNonNull(config, "config"),
            DEFAULT_SCOPE
        );
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
    public Optional<EntityRecord> loadEntity(String entityId) {
        return delegate.loadEntity(entityId);
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        return delegate.loadRelation(relationId);
    }

    @Override
    public List<EntityRecord> allEntities() {
        return delegate.allEntities();
    }

    @Override
    public List<RelationRecord> allRelations() {
        return delegate.allRelations();
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        return delegate.findRelations(entityId);
    }

    public Neo4jGraphSnapshot captureSnapshot() {
        return delegate.captureSnapshot();
    }

    public void restore(Neo4jGraphSnapshot snapshot) {
        delegate.restore(snapshot);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
