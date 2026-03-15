package io.github.lightragjava.storage;

public interface AtomicStorageProvider extends StorageProvider {
    <T> T writeAtomically(AtomicOperation<T> operation);

    @FunctionalInterface
    interface AtomicOperation<T> {
        T execute(StorageProvider storageProvider);
    }
}
