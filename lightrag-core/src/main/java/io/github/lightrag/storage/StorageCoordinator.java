package io.github.lightrag.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class StorageCoordinator implements AtomicStorageProvider, AutoCloseable {
    private static final Comparator<VectorStore.VectorMatch> VECTOR_MATCH_ORDER =
        Comparator.comparingDouble(VectorStore.VectorMatch::score).reversed().thenComparing(VectorStore.VectorMatch::id);

    private final RelationalStorageAdapter relationalAdapter;
    private final GraphStorageAdapter graphAdapter;
    private final VectorStorageAdapter vectorAdapter;

    public StorageCoordinator(
        RelationalStorageAdapter relationalAdapter,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        this.relationalAdapter = Objects.requireNonNull(relationalAdapter, "relationalAdapter");
        this.graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
        this.vectorAdapter = Objects.requireNonNull(vectorAdapter, "vectorAdapter");
    }

    @Override
    public DocumentStore documentStore() {
        return relationalAdapter.documentStore();
    }

    @Override
    public ChunkStore chunkStore() {
        return relationalAdapter.chunkStore();
    }

    @Override
    public GraphStore graphStore() {
        return graphAdapter.graphStore();
    }

    @Override
    public VectorStore vectorStore() {
        return vectorAdapter.vectorStore();
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return relationalAdapter.documentStatusStore();
    }

    @Override
    public SnapshotStore snapshotStore() {
        return relationalAdapter.snapshotStore();
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        var relationalSnapshot = relationalAdapter.captureSnapshot();
        var graphSnapshot = graphAdapter.captureSnapshot();
        var vectorSnapshot = vectorAdapter.captureSnapshot();
        try {
            return relationalAdapter.writeInTransaction(storage -> {
                var stagedGraphStore = new StagedGraphStore(graphAdapter.graphStore());
                var stagedVectorStore = new StagedVectorStore(vectorAdapter.vectorStore());
                var result = operation.execute(new AtomicView(
                    storage.documentStore(),
                    storage.chunkStore(),
                    stagedGraphStore,
                    stagedVectorStore,
                    storage.documentStatusStore()
                ));
                graphAdapter.apply(stagedGraphStore.toWrites());
                vectorAdapter.apply(stagedVectorStore.toWrites());
                return result;
            });
        } catch (RuntimeException | Error failure) {
            rollback(relationalSnapshot, graphSnapshot, vectorSnapshot, failure);
            throw failure;
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        relationalAdapter.restore(new SnapshotStore.Snapshot(source.documents(), source.chunks(), List.of(), List.of(), Map.of(), source.documentStatuses()));
        graphAdapter.restore(new GraphStorageAdapter.GraphSnapshot(source.entities(), source.relations()));
        vectorAdapter.restore(new VectorStorageAdapter.VectorSnapshot(source.vectors()));
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            graphAdapter.close();
        } catch (RuntimeException exception) {
            failure = exception;
        } catch (Exception exception) {
            failure = new IllegalStateException("Failed to close graphAdapter", exception);
        }
        try {
            vectorAdapter.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("Failed to close vectorAdapter", exception);
            if (failure == null) {
                failure = wrapped;
            } else {
                failure.addSuppressed(wrapped);
            }
        }
        try {
            relationalAdapter.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("Failed to close relationalAdapter", exception);
            if (failure == null) {
                failure = wrapped;
            } else {
                failure.addSuppressed(wrapped);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }

    private void rollback(
        SnapshotStore.Snapshot relationalSnapshot,
        GraphStorageAdapter.GraphSnapshot graphSnapshot,
        VectorStorageAdapter.VectorSnapshot vectorSnapshot,
        Throwable failure
    ) {
        try {
            relationalAdapter.restore(relationalSnapshot);
        } catch (RuntimeException | Error restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        try {
            graphAdapter.restore(graphSnapshot);
        } catch (RuntimeException | Error restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
        try {
            vectorAdapter.restore(vectorSnapshot);
        } catch (RuntimeException | Error restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private static final class StagedGraphStore implements GraphStore {
        private final GraphStore delegate;
        private final Map<String, EntityRecord> stagedEntities = new LinkedHashMap<>();
        private final Map<String, RelationRecord> stagedRelations = new LinkedHashMap<>();

        private StagedGraphStore(GraphStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            var record = Objects.requireNonNull(entity, "entity");
            stagedEntities.put(record.id(), record);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            var record = Objects.requireNonNull(relation, "relation");
            stagedRelations.put(record.id(), record);
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            var id = Objects.requireNonNull(entityId, "entityId");
            if (stagedEntities.containsKey(id)) {
                return Optional.ofNullable(stagedEntities.get(id));
            }
            return delegate.loadEntity(id);
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            var id = Objects.requireNonNull(relationId, "relationId");
            if (stagedRelations.containsKey(id)) {
                return Optional.ofNullable(stagedRelations.get(id));
            }
            return delegate.loadRelation(id);
        }

        @Override
        public List<EntityRecord> allEntities() {
            var merged = new LinkedHashMap<String, EntityRecord>();
            for (var entity : delegate.allEntities()) {
                merged.put(entity.id(), entity);
            }
            merged.putAll(stagedEntities);
            return List.copyOf(merged.values());
        }

        @Override
        public List<RelationRecord> allRelations() {
            var merged = new LinkedHashMap<String, RelationRecord>();
            for (var relation : delegate.allRelations()) {
                merged.put(relation.id(), relation);
            }
            merged.putAll(stagedRelations);
            return List.copyOf(merged.values());
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            var id = Objects.requireNonNull(entityId, "entityId");
            return allRelations().stream()
                .filter(relation -> relation.sourceEntityId().equals(id) || relation.targetEntityId().equals(id))
                .toList();
        }

        private GraphStorageAdapter.StagedGraphWrites toWrites() {
            if (stagedEntities.isEmpty() && stagedRelations.isEmpty()) {
                return GraphStorageAdapter.StagedGraphWrites.empty();
            }
            return new GraphStorageAdapter.StagedGraphWrites(
                new ArrayList<>(stagedEntities.values()),
                new ArrayList<>(stagedRelations.values())
            );
        }
    }

    private static final class StagedVectorStore implements VectorStore {
        private final VectorStore delegate;
        private final Map<String, Map<String, VectorRecord>> stagedNamespaces = new LinkedHashMap<>();

        private StagedVectorStore(VectorStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            var ns = Objects.requireNonNull(namespace, "namespace");
            Objects.requireNonNull(vectors, "vectors");
            var namespaceRecords = ensureNamespaceRecords(ns);
            for (var vector : vectors) {
                var record = Objects.requireNonNull(vector, "vector");
                namespaceRecords.put(record.id(), record);
            }
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            var ns = Objects.requireNonNull(namespace, "namespace");
            var query = List.copyOf(Objects.requireNonNull(queryVector, "queryVector"));
            if (topK <= 0) {
                return List.of();
            }
            if (!stagedNamespaces.containsKey(ns)) {
                return delegate.search(ns, query, topK);
            }
            return stagedNamespaces.get(ns).values().stream()
                .map(record -> new VectorMatch(record.id(), dotProduct(query, record.vector())))
                .sorted(VECTOR_MATCH_ORDER)
                .limit(topK)
                .toList();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            var ns = Objects.requireNonNull(namespace, "namespace");
            if (!stagedNamespaces.containsKey(ns)) {
                return delegate.list(ns);
            }
            return List.copyOf(stagedNamespaces.get(ns).values());
        }

        private VectorStorageAdapter.StagedVectorWrites toWrites() {
            if (stagedNamespaces.isEmpty()) {
                return VectorStorageAdapter.StagedVectorWrites.empty();
            }
            var upserts = new LinkedHashMap<String, List<HybridVectorStore.EnrichedVectorRecord>>();
            var namespacesToReset = new ArrayList<String>();
            for (var entry : stagedNamespaces.entrySet()) {
                namespacesToReset.add(entry.getKey());
                var enrichedRecords = entry.getValue().values().stream()
                    .map(record -> new HybridVectorStore.EnrichedVectorRecord(record.id(), record.vector(), "", List.of()))
                    .toList();
                upserts.put(entry.getKey(), enrichedRecords);
            }
            return new VectorStorageAdapter.StagedVectorWrites(upserts, namespacesToReset);
        }

        private Map<String, VectorRecord> ensureNamespaceRecords(String namespace) {
            return stagedNamespaces.computeIfAbsent(namespace, key -> {
                var merged = new LinkedHashMap<String, VectorRecord>();
                for (var record : delegate.list(key)) {
                    merged.put(record.id(), record);
                }
                return merged;
            });
        }

        private static double dotProduct(List<Double> left, List<Double> right) {
            if (left.size() != right.size()) {
                throw new IllegalArgumentException("vector dimensions must match");
            }
            double score = 0.0d;
            for (int index = 0; index < left.size(); index++) {
                score += left.get(index) * right.get(index);
            }
            return score;
        }
    }
}
