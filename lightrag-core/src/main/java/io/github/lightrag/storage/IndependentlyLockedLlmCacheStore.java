package io.github.lightrag.storage;

import io.github.lightrag.storage.LlmCacheStore.CacheRecord;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Guards an {@link LlmCacheStore} with a lock private to the cache.
 *
 * <p>The cache is content-addressed and is not part of any storage snapshot, so it must not share the
 * provider-wide lock that {@code writeAtomically} holds: merge-time summary calls run on worker threads
 * while the committing thread waits for them, and sharing that lock deadlocks the workers.</p>
 */
public final class IndependentlyLockedLlmCacheStore implements LlmCacheStore {
    private final LlmCacheStore delegate;
    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);

    public IndependentlyLockedLlmCacheStore(LlmCacheStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void save(CacheRecord record) {
        withWriteLock(() -> delegate.save(record));
    }

    @Override
    public Optional<CacheRecord> load(String cacheId) {
        return withReadLock(() -> delegate.load(cacheId));
    }

    @Override
    public boolean contains(String cacheId) {
        return withReadLock(() -> delegate.contains(cacheId));
    }

    @Override
    public void delete(List<String> cacheIds) {
        withWriteLock(() -> delegate.delete(cacheIds));
    }

    @Override
    public void drop() {
        withWriteLock(delegate::drop);
    }

    private void withWriteLock(Runnable runnable) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            runnable.run();
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T withReadLock(java.util.function.Supplier<T> supplier) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return supplier.get();
        } finally {
            readLock.unlock();
        }
    }
}
