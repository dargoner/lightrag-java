package io.github.lightrag.storage.memgraph;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link GraphStorageAdapter} that projects the workspace's knowledge graph into a Memgraph
 * instance over Bolt. The relational graph rows stay the durable source of truth, exactly as they
 * are for the other graph projections.
 *
 * <p>Memgraph shares the Bolt/Cypher statement set and transaction semantics of the Neo4j
 * projection (see {@link WorkspaceScopedMemgraphGraphStore}), so snapshot and pre-image handling
 * mirror {@code Neo4jGraphStorageAdapter}; only the bootstrap DDL differs.</p>
 */
public final class MemgraphGraphStorageAdapter implements GraphStorageAdapter {
    private final WorkspaceScopedMemgraphGraphStore graphStore;

    /** Creates the store (and its driver) for the workspace; both are closed by {@link #close()}. */
    public MemgraphGraphStorageAdapter(MemgraphGraphConfig config, String workspaceId) {
        this(new WorkspaceScopedMemgraphGraphStore(config, new WorkspaceScope(workspaceId)));
    }

    /** Takes over the given store; ownership transfers to this adapter. */
    public MemgraphGraphStorageAdapter(WorkspaceScopedMemgraphGraphStore graphStore) {
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public GraphSnapshot captureSnapshot() {
        var snapshot = graphStore.captureSnapshot();
        return new GraphSnapshot(snapshot.entities(), snapshot.relations());
    }

    @Override
    public void apply(StagedGraphWrites writes) {
        var source = Objects.requireNonNull(writes, "writes");
        // Entities first: relation writes fail when an endpoint is absent, so entities must land first.
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
        graphStore.restore(new Neo4jGraphSnapshot(source.entities(), source.relations()));
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
     * Entities are restored before relations because {@code deleteEntities} issues a DETACH DELETE: an absent
     * entity takes any relation attached to it down with it, and the relation pass re-writes the pre-image
     * relations afterwards.
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
