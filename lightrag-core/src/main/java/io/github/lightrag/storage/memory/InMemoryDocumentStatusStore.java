package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.DocumentStatusStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryDocumentStatusStore implements DocumentStatusStore {
    private final ReadWriteLock lock;
    private final ConcurrentNavigableMap<String, StatusRecord> statuses = new ConcurrentSkipListMap<>();

    public InMemoryDocumentStatusStore() {
        this(null);
    }

    public InMemoryDocumentStatusStore(ReadWriteLock lock) {
        this.lock = lock;
    }

    @Override
    public void save(StatusRecord statusRecord) {
        withWriteLock(() -> statuses.put(statusRecord.documentId(), Objects.requireNonNull(statusRecord, "statusRecord")));
    }

    @Override
    public Optional<StatusRecord> load(String documentId) {
        return withReadLock(() -> Optional.ofNullable(statuses.get(Objects.requireNonNull(documentId, "documentId"))));
    }

    @Override
    public List<StatusRecord> list() {
        return withReadLock(() -> List.copyOf(statuses.values()));
    }

    @Override
    public void delete(String documentId) {
        withWriteLock(() -> statuses.remove(Objects.requireNonNull(documentId, "documentId")));
    }

    public List<StatusRecord> snapshot() {
        return list();
    }

    public void restore(List<StatusRecord> records) {
        withWriteLock(() -> {
            statuses.clear();
            for (var record : List.copyOf(records)) {
                statuses.put(record.documentId(), record);
            }
        });
    }

    private void withWriteLock(Runnable runnable) {
        if (lock == null) {
            runnable.run();
            return;
        }
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            runnable.run();
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T withReadLock(java.util.concurrent.Callable<T> callable) {
        if (lock == null) {
            try {
                return callable.call();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return callable.call();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        } finally {
            readLock.unlock();
        }
    }
}
