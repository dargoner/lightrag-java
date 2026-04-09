package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.TaskStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryTaskStore implements TaskStore {
    private final ReadWriteLock lock;
    private final ConcurrentNavigableMap<String, TaskRecord> tasks = new ConcurrentSkipListMap<>();

    public InMemoryTaskStore() {
        this(null);
    }

    public InMemoryTaskStore(ReadWriteLock lock) {
        this.lock = lock;
    }

    @Override
    public void save(TaskRecord taskRecord) {
        withWriteLock(() -> tasks.put(taskRecord.taskId(), Objects.requireNonNull(taskRecord, "taskRecord")));
    }

    @Override
    public Optional<TaskRecord> load(String taskId) {
        return withReadLock(() -> Optional.ofNullable(tasks.get(Objects.requireNonNull(taskId, "taskId"))));
    }

    @Override
    public List<TaskRecord> list() {
        return withReadLock(() -> List.copyOf(tasks.values()));
    }

    @Override
    public void delete(String taskId) {
        withWriteLock(() -> tasks.remove(Objects.requireNonNull(taskId, "taskId")));
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
