package io.github.lightrag.storage;

import java.util.Objects;

public final class StorageCoordinator implements AtomicStorageProvider {
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
        var atomicOperation = Objects.requireNonNull(operation, "operation");
        return relationalAdapter.writeInTransaction(relationalStorage -> atomicOperation.execute(new AtomicView(
            relationalStorage.documentStore(),
            relationalStorage.chunkStore(),
            graphAdapter.graphStore(),
            vectorAdapter.vectorStore(),
            relationalStorage.documentStatusStore()
        )));
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        relationalAdapter.restore(Objects.requireNonNull(snapshot, "snapshot"));
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }
}
