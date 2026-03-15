package io.github.lightragjava.storage;

public interface AtomicStorageProvider extends StorageProvider {
    <T> T writeAtomically(AtomicOperation<T> operation);

    void restore(SnapshotStore.Snapshot snapshot);

    interface AtomicStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        GraphStore graphStore();

        VectorStore vectorStore();

        DocumentStatusStore documentStatusStore();
    }

    @FunctionalInterface
    interface AtomicOperation<T> {
        T execute(AtomicStorageView storage);
    }
}
