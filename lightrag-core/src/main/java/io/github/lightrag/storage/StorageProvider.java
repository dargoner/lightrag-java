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
        return new io.github.lightrag.storage.memory.InMemoryDocumentGraphSnapshotStore();
    }

    default DocumentGraphJournalStore documentGraphJournalStore() {
        return new io.github.lightrag.storage.memory.InMemoryDocumentGraphJournalStore();
    }
}
