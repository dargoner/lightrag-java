package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.EmbeddingSpaceStore;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryEmbeddingSpaceStore implements EmbeddingSpaceStore {
    private final ReadWriteLock lock;
    private volatile Marker marker;

    public InMemoryEmbeddingSpaceStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void save(Marker marker) {
        var record = Objects.requireNonNull(marker, "marker");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            this.marker = record;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<Marker> load() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(marker);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void delete() {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            marker = null;
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<Marker> snapshot() {
        return load();
    }

    public void restore(Optional<Marker> snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            marker = source.orElse(null);
        } finally {
            writeLock.unlock();
        }
    }
}
