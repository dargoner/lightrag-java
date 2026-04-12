package io.github.lightrag.storage;

public interface AtomicStorageProvider extends StorageProvider {
    <T> T writeAtomically(AtomicOperation<T> operation);

    void restore(SnapshotStore.Snapshot snapshot);

    interface AtomicStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        DocumentGraphSnapshotStore documentGraphSnapshotStore();

        DocumentGraphJournalStore documentGraphJournalStore();

        GraphStore graphStore();

        VectorStore vectorStore();

        DocumentStatusStore documentStatusStore();
    }

    @FunctionalInterface
    interface AtomicOperation<T> {
        T execute(AtomicStorageView storage);
    }
}
