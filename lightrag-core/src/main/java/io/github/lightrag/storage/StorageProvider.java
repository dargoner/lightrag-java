package io.github.lightrag.storage;

public interface StorageProvider {
    String DOCUMENT_GRAPH_SNAPSHOT_STORE_UNSUPPORTED_MESSAGE =
        "documentGraphSnapshotStore is not supported by this provider";
    String DOCUMENT_GRAPH_JOURNAL_STORE_UNSUPPORTED_MESSAGE =
        "documentGraphJournalStore is not supported by this provider";

    DocumentStore documentStore();

    ChunkStore chunkStore();

    GraphStore graphStore();

    VectorStore vectorStore();

    DocumentStatusStore documentStatusStore();

    TaskStore taskStore();

    TaskStageStore taskStageStore();

    SnapshotStore snapshotStore();

    default DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        throw new UnsupportedOperationException(DOCUMENT_GRAPH_SNAPSHOT_STORE_UNSUPPORTED_MESSAGE);
    }

    default DocumentGraphJournalStore documentGraphJournalStore() {
        throw new UnsupportedOperationException(DOCUMENT_GRAPH_JOURNAL_STORE_UNSUPPORTED_MESSAGE);
    }
}
