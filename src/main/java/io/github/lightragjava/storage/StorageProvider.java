package io.github.lightragjava.storage;

public interface StorageProvider {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    GraphStore graphStore();

    VectorStore vectorStore();

    DocumentStatusStore documentStatusStore();

    SnapshotStore snapshotStore();
}
