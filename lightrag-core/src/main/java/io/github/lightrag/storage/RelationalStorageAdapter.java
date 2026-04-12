package io.github.lightrag.storage;

import java.util.Optional;

public interface RelationalStorageAdapter extends AutoCloseable {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    DocumentStatusStore documentStatusStore();

    TaskStore taskStore();

    TaskStageStore taskStageStore();

    SnapshotStore snapshotStore();

    default DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        throw new UnsupportedOperationException("documentGraphSnapshotStore is not available in this relational adapter");
    }

    default DocumentGraphJournalStore documentGraphJournalStore() {
        throw new UnsupportedOperationException("documentGraphJournalStore is not available in this relational adapter");
    }

    SnapshotStore.Snapshot captureSnapshot();

    void restore(SnapshotStore.Snapshot snapshot);

    default SnapshotStore.Snapshot toRelationalRestoreSnapshot(SnapshotStore.Snapshot snapshot) {
        return new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        );
    }

    <T> T writeInTransaction(RelationalWriteOperation<T> operation);

    interface RelationalStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        DocumentStatusStore documentStatusStore();

        default TaskStore taskStore() {
            throw new UnsupportedOperationException("taskStore is not available in relational transaction view");
        }

        default TaskStageStore taskStageStore() {
            throw new UnsupportedOperationException("taskStageStore is not available in relational transaction view");
        }

        default Optional<GraphStore> transactionalGraphStore() {
            return Optional.empty();
        }

        default Optional<VectorStore> transactionalVectorStore() {
            return Optional.empty();
        }
    }

    @FunctionalInterface
    interface RelationalWriteOperation<T> {
        T execute(RelationalStorageView storage);
    }

    @Override
    default void close() {
    }
}
