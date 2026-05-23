package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.LlmCacheStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryLlmCacheStore implements LlmCacheStore {
    private final ReadWriteLock lock;
    private final ConcurrentMap<String, CacheRecord> records = new ConcurrentHashMap<>();

    public InMemoryLlmCacheStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void save(CacheRecord record) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            records.put(record.id(), record);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<CacheRecord> load(String cacheId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(records.get(Objects.requireNonNull(cacheId, "cacheId")));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean contains(String cacheId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return records.containsKey(Objects.requireNonNull(cacheId, "cacheId"));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void delete(List<String> cacheIds) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            for (var cacheId : Objects.requireNonNull(cacheIds, "cacheIds")) {
                records.remove(cacheId);
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void drop() {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            records.clear();
        } finally {
            writeLock.unlock();
        }
    }
}
