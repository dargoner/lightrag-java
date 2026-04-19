package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.TaskDocumentStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryTaskDocumentStore implements TaskDocumentStore {
    private final ReadWriteLock lock;
    private final ConcurrentNavigableMap<String, TaskDocumentRecord> records = new ConcurrentSkipListMap<>();

    public InMemoryTaskDocumentStore() {
        this(null);
    }

    public InMemoryTaskDocumentStore(ReadWriteLock lock) {
        this.lock = lock;
    }

    @Override
    public void save(TaskDocumentRecord record) {
        withWriteLock(() -> records.put(key(record.taskId(), record.documentId()), Objects.requireNonNull(record, "record")));
    }

    @Override
    public Optional<TaskDocumentRecord> load(String taskId, String documentId) {
        return withReadLock(() -> Optional.ofNullable(records.get(key(taskId, documentId))));
    }

    @Override
    public List<TaskDocumentRecord> listByTask(String taskId) {
        var prefix = Objects.requireNonNull(taskId, "taskId") + "::";
        return withReadLock(() -> records.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .map(java.util.Map.Entry::getValue)
            .toList());
    }

    @Override
    public void deleteByTask(String taskId) {
        var prefix = Objects.requireNonNull(taskId, "taskId") + "::";
        withWriteLock(() -> records.keySet().removeIf(key -> key.startsWith(prefix)));
    }

    private static String key(String taskId, String documentId) {
        return Objects.requireNonNull(taskId, "taskId") + "::" + Objects.requireNonNull(documentId, "documentId");
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
