package io.github.lightrag.storage;

public interface StorageProvider {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    GraphStore graphStore();

    VectorStore vectorStore();

    DocumentStatusStore documentStatusStore();

    SnapshotStore snapshotStore();
}
