package io.github.lightrag.storage;

public interface RelationalStorageAdapter {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    DocumentStatusStore documentStatusStore();

    SnapshotStore snapshotStore();

    SnapshotStore.Snapshot captureSnapshot();

    void restore(SnapshotStore.Snapshot snapshot);

    <T> T writeInTransaction(RelationalWriteOperation<T> operation);

    interface RelationalStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        DocumentStatusStore documentStatusStore();
    }

    @FunctionalInterface
    interface RelationalWriteOperation<T> {
        T execute(RelationalStorageView storage);
    }
}
