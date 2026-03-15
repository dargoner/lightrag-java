package io.github.lightragjava.storage;

public interface IngestStorageProvider extends StorageProvider {
    @Override
    RollbackCapableDocumentStore documentStore();

    @Override
    RollbackCapableChunkStore chunkStore();
}
