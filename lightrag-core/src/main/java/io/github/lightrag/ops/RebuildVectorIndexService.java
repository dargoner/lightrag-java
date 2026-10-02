package io.github.lightrag.ops;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.VectorSpaceMismatchException;
import io.github.lightrag.indexing.GraphVectorIndexer;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.EmbeddingSpaceStore;
import io.github.lightrag.storage.FixedWorkspaceStorageProvider;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The offline rebuild path behind the {@code rebuild-vdb} CLI: it re-embeds every vector namespace from its
 * authoritative source (chunks from the chunk store, entities and relations from the graph store) using the
 * exact payload builders ingestion uses, and reports the drift a {@code check} run would have found.
 *
 * <p>All writers on the workspace must be stopped before a rebuild: the three vector namespaces are replaced
 * as a whole, so a concurrent ingestion writing the same namespaces would be lost.
 */
public final class RebuildVectorIndexService {
    private final WorkspaceStorageProvider storage;
    private final EmbeddingModel embeddingModel;
    private final LlmConcurrencyBudget budget;
    private final boolean force;
    private final int embeddingBatchSize;

    public RebuildVectorIndexService(
        WorkspaceStorageProvider storage,
        EmbeddingModel embeddingModel,
        LlmConcurrencyBudget budget,
        boolean force
    ) {
        this(storage, embeddingModel, budget, force, Integer.MAX_VALUE);
    }

    public RebuildVectorIndexService(
        WorkspaceStorageProvider storage,
        EmbeddingModel embeddingModel,
        LlmConcurrencyBudget budget,
        boolean force,
        int embeddingBatchSize
    ) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.force = force;
        if (embeddingBatchSize <= 0) {
            throw new IllegalArgumentException("embeddingBatchSize must be positive");
        }
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public RebuildVectorIndexService(
        AtomicStorageProvider storageProvider,
        EmbeddingModel embeddingModel,
        LlmConcurrencyBudget budget,
        boolean force
    ) {
        this(new FixedWorkspaceStorageProvider(storageProvider), embeddingModel, budget, force);
    }

    /**
     * Reports the vector drift of the workspace without writing anything. Read-only, so it carries no
     * marker or {@code --force} guard.
     */
    public RebuildReport check(String workspaceId) {
        return inspect(resolve(workspaceId));
    }

    /**
     * Replaces the three vector namespaces with vectors regenerated from the authoritative stores. Requires
     * {@code --force} when no embedding space marker is recorded (or the provider has no marker store), and
     * when the recorded marker describes a different embedding space.
     */
    public RebuildReport rebuild(String workspaceId) {
        var provider = resolve(workspaceId);
        var chunks = provider.chunkStore().list().stream().map(GraphVectorIndexer::toChunk).toList();
        var entities = provider.graphStore().allEntities().stream().map(GraphVectorIndexer::toEntity).toList();
        var relations = provider.graphStore().allRelations().stream().map(GraphVectorIndexer::toRelation).toList();
        var drift = inspect(provider);
        var spaceStore = markerStore(provider);
        var marker = spaceStore.flatMap(EmbeddingSpaceStore::load);
        var identity = currentIdentity();
        if (marker.isEmpty() && !force) {
            throw new IllegalStateException(
                "Refusing to rebuild the vector index: "
                    + (spaceStore.isPresent()
                        ? "no embedding space marker is recorded"
                        : "this storage provider has no embedding space store")
                    + "; pass --force to rebuild anyway (stop all writers first)"
            );
        }
        if (marker.isPresent() && !marker.get().modelIdentity().equals(identity) && !force) {
            throw mismatch("the recorded marker describes a different embedding model", marker.get(), identity, -1);
        }


        var limitedEmbeddingModel = budget.limitEmbedding(LlmConcurrencyBudget.EmbeddingPriority.LOW, embeddingModel);
        var chunkVectors = GraphVectorIndexer.chunkVectors(limitedEmbeddingModel, embeddingBatchSize, chunks);
        var entityVectors = GraphVectorIndexer.entityVectors(limitedEmbeddingModel, embeddingBatchSize, entities);
        var relationVectors = GraphVectorIndexer.relationVectors(limitedEmbeddingModel, embeddingBatchSize, relations);
        var dimensions = firstDimensions(chunkVectors, entityVectors, relationVectors);
        if (marker.isPresent() && dimensions > 0 && marker.get().dimensions() != dimensions && !force) {
            throw mismatch("the recorded marker has a different dimension count", marker.get(), identity, dimensions);
        }

        var snapshot = StorageSnapshots.capture(provider);
        var updatedVectors = new LinkedHashMap<>(snapshot.vectors());
        updatedVectors.put(StorageSnapshots.CHUNK_NAMESPACE, chunkVectors);
        updatedVectors.put(StorageSnapshots.ENTITY_NAMESPACE, entityVectors);
        updatedVectors.put(StorageSnapshots.RELATION_NAMESPACE, relationVectors);
        provider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities(),
            snapshot.relations(),
            Map.copyOf(updatedVectors),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        var vectorStore = provider.vectorStore();
        if (vectorStore instanceof HybridVectorStore) {
            GraphVectorIndexer.saveChunkVectors(chunks, chunkVectors, vectorStore);
            GraphVectorIndexer.saveEntityVectors(entities, entityVectors, vectorStore);
            GraphVectorIndexer.saveRelationVectors(relations, relationVectors, vectorStore);
        }
        // Clearing the marker lets the next ingest re-record the space the rebuilt vectors were made with.
        spaceStore.ifPresent(EmbeddingSpaceStore::delete);
        return new RebuildReport(
            drift.missingChunkVectorIds(),
            drift.staleChunkVectorIds(),
            drift.missingEntityVectorIds(),
            drift.staleEntityVectorIds(),
            drift.missingRelationVectorIds(),
            drift.staleRelationVectorIds(),
            chunkVectors.size() + entityVectors.size() + relationVectors.size()
        );
    }

    private AtomicStorageProvider resolve(String workspaceId) {
        return Objects.requireNonNull(
            storage.forWorkspace(new WorkspaceScope(Objects.requireNonNull(workspaceId, "workspaceId"))),
            "storage.forWorkspace"
        );
    }

    private RebuildReport inspect(StorageProvider provider) {
        var vectorStore = provider.vectorStore();
        var chunkIds = provider.chunkStore().list().stream().map(ChunkStore.ChunkRecord::id).toList();
        var entityIds = provider.graphStore().allEntities().stream().map(GraphStore.EntityRecord::id).toList();
        var relationIds = provider.graphStore().allRelations().stream().map(GraphStore.RelationRecord::id).toList();
        var chunkDrift = drift(chunkIds, vectorIds(vectorStore, StorageSnapshots.CHUNK_NAMESPACE));
        var entityDrift = drift(entityIds, vectorIds(vectorStore, StorageSnapshots.ENTITY_NAMESPACE));
        var relationDrift = drift(relationIds, vectorIds(vectorStore, StorageSnapshots.RELATION_NAMESPACE));
        return new RebuildReport(
            chunkDrift.missing,
            chunkDrift.stale,
            entityDrift.missing,
            entityDrift.stale,
            relationDrift.missing,
            relationDrift.stale,
            0
        );
    }

    private static List<String> vectorIds(VectorStore vectorStore, String namespace) {
        return vectorStore.list(namespace).stream().map(VectorStore.VectorRecord::id).toList();
    }

    private static Drift drift(List<String> sourceIds, List<String> vectorIds) {
        var vectors = new LinkedHashSet<>(vectorIds);
        var sources = new LinkedHashSet<>(sourceIds);
        var missing = sourceIds.stream().filter(id -> !vectors.contains(id)).distinct().toList();
        var stale = vectorIds.stream().filter(id -> !sources.contains(id)).distinct().toList();
        return new Drift(missing, stale);
    }

    private Optional<EmbeddingSpaceStore> markerStore(StorageProvider provider) {
        try {
            return Optional.of(provider.embeddingSpaceStore());
        } catch (UnsupportedOperationException exception) {
            if (!Objects.equals(exception.getMessage(), StorageProvider.EMBEDDING_SPACE_STORE_UNSUPPORTED_MESSAGE)) {
                throw exception;
            }
            return Optional.empty();
        }
    }

    private String currentIdentity() {
        var identity = embeddingModel.cacheIdentity();
        return identity == null || identity.isBlank() ? "unknown" : identity;
    }

    @SafeVarargs
    private static int firstDimensions(List<VectorStore.VectorRecord>... batches) {
        for (var batch : batches) {
            if (!batch.isEmpty()) {
                return batch.get(0).vector().size();
            }
        }
        return -1;
    }

    private static VectorSpaceMismatchException mismatch(
        String reason,
        EmbeddingSpaceStore.Marker marker,
        String identity,
        int dimensions
    ) {
        var produced = dimensions > 0 ? "'%s' (%d dimensions)".formatted(identity, dimensions) : "'%s'".formatted(identity);
        return new VectorSpaceMismatchException(
            "Embedding space guard refused the rebuild because " + reason
                + ": the stored marker recorded '" + marker.modelIdentity() + "' (" + marker.dimensions()
                + " dimensions) but the configured embedding model produces " + produced
                + "; pass --force to rebuild the vector index with the configured model"
        );
    }

    public record RebuildReport(
        List<String> missingChunkVectorIds,
        List<String> staleChunkVectorIds,
        List<String> missingEntityVectorIds,
        List<String> staleEntityVectorIds,
        List<String> missingRelationVectorIds,
        List<String> staleRelationVectorIds,
        int embeddedItems
    ) {
        public RebuildReport {
            missingChunkVectorIds = List.copyOf(Objects.requireNonNull(missingChunkVectorIds, "missingChunkVectorIds"));
            staleChunkVectorIds = List.copyOf(Objects.requireNonNull(staleChunkVectorIds, "staleChunkVectorIds"));
            missingEntityVectorIds = List.copyOf(Objects.requireNonNull(missingEntityVectorIds, "missingEntityVectorIds"));
            staleEntityVectorIds = List.copyOf(Objects.requireNonNull(staleEntityVectorIds, "staleEntityVectorIds"));
            missingRelationVectorIds = List.copyOf(Objects.requireNonNull(missingRelationVectorIds, "missingRelationVectorIds"));
            staleRelationVectorIds = List.copyOf(Objects.requireNonNull(staleRelationVectorIds, "staleRelationVectorIds"));
            if (embeddedItems < 0) {
                throw new IllegalArgumentException("embeddedItems must not be negative");
            }
        }

        public int missingItems() {
            return missingChunkVectorIds.size() + missingEntityVectorIds.size() + missingRelationVectorIds.size();
        }

        public int staleItems() {
            return staleChunkVectorIds.size() + staleEntityVectorIds.size() + staleRelationVectorIds.size();
        }

        public boolean clean() {
            return missingItems() == 0 && staleItems() == 0;
        }

        public String describe() {
            return "missing=" + missingItems() + " stale=" + staleItems() + " embedded=" + embeddedItems
                + " (chunks missing=" + missingChunkVectorIds.size() + " stale=" + staleChunkVectorIds.size()
                + ", entities missing=" + missingEntityVectorIds.size() + " stale=" + staleEntityVectorIds.size()
                + ", relations missing=" + missingRelationVectorIds.size() + " stale=" + staleRelationVectorIds.size()
                + ")";
        }
    }

    private record Drift(List<String> missing, List<String> stale) {
    }
}
