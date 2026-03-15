package io.github.lightragjava.storage;

public interface AtomicStorageProvider extends StorageProvider {
    <T> T writeAtomically(AtomicOperation<T> operation);

    interface AtomicStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        GraphStore graphStore();

        VectorStore vectorStore();
    }

    @FunctionalInterface
    interface AtomicOperation<T> {
        T execute(AtomicStorageView storage);
    }
}
