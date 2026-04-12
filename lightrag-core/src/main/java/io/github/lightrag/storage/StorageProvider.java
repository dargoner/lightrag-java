package io.github.lightrag.storage;

public interface StorageProvider {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    GraphStore graphStore();

    VectorStore vectorStore();

    DocumentStatusStore documentStatusStore();

    TaskStore taskStore();

    TaskStageStore taskStageStore();

    SnapshotStore snapshotStore();

    default DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        throw new UnsupportedOperationException("documentGraphSnapshotStore is not supported");
    }

    default DocumentGraphJournalStore documentGraphJournalStore() {
        throw new UnsupportedOperationException("documentGraphJournalStore is not supported");
    }
}
