package io.github.lightrag.storage.nebula;

import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link GraphStorageAdapter} that projects the workspace's knowledge graph into a NebulaGraph
 * space. The relational graph rows stay the durable source of truth, exactly as they are for the
 * other graph projections.
 *
 * <p>NebulaGraph has no cross-statement transaction for DML, so {@link #restore} and
 * {@link #restorePreImage} apply statement by statement; a failure part-way leaves the partial
 * state in place rather than rolling it back.</p>
 */
public final class NebulaGraphStorageAdapter implements GraphStorageAdapter {
    private final NebulaGraphStore graphStore;

    /** Creates the store (and its session pool) for the workspace; both are closed by {@link #close()}. */
    public NebulaGraphStorageAdapter(NebulaGraphConfig config, String workspaceId) {
        this(new NebulaGraphStore(config, workspaceId));
    }

    /** Takes over the given store; ownership transfers to this adapter. */
    public NebulaGraphStorageAdapter(NebulaGraphStore graphStore) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
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
        // Entities first so a real entity lands with its properties; relations then project any
        // still-missing endpoints as placeholder vertices (materialized = false).
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
        graphStore.clear();
        graphStore.saveEntities(source.entities());
        graphStore.saveRelations(source.relations());
    }

    @Override
    public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
        var requestedEntityIds = List.copyOf(entityIds);
        var requestedRelationIds = List.copyOf(relationIds);
        return Optional.of(new ScopedPreImage(
            requestedEntityIds,
            graphStore.loadEntities(requestedEntityIds),
            requestedRelationIds,
            graphStore.loadRelations(requestedRelationIds)
        ));
    }

    /**
     * Entities are restored before relations because {@code deleteEntities} deletes vertices
     * {@code WITH EDGE}: an absent entity takes any relation attached to it down with it, and the
     * relation pass re-writes the pre-image relations afterwards.
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
            graphStore.deleteEntities(absentEntityIds);
        }
        if (!scoped.entities().isEmpty()) {
            graphStore.saveEntities(scoped.entities());
        }
        var presentRelationIds = scoped.relations().stream()
            .map(GraphStore.RelationRecord::id)
            .collect(Collectors.toSet());
        var absentRelationIds = scoped.relationIds().stream()
            .filter(id -> !presentRelationIds.contains(id))
            .toList();
        if (!absentRelationIds.isEmpty()) {
            graphStore.deleteRelations(absentRelationIds);
        }
        if (!scoped.relations().isEmpty()) {
            graphStore.saveRelations(scoped.relations());
        }
    }

    @Override
    public void close() {
        graphStore.close();
    }

    private record ScopedPreImage(
        List<String> entityIds,
        List<GraphStore.EntityRecord> entities,
        List<String> relationIds,
        List<GraphStore.RelationRecord> relations
    ) implements PreImage {
    }
}
