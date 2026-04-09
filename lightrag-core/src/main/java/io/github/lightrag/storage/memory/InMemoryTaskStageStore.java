package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.TaskStageStore;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;

public final class InMemoryTaskStageStore implements TaskStageStore {
    private final ReadWriteLock lock;
    private final ConcurrentMap<String, ConcurrentMap<String, TaskStageRecord>> stagesByTask = new ConcurrentHashMap<>();

    public InMemoryTaskStageStore() {
        this(null);
    }

    public InMemoryTaskStageStore(ReadWriteLock lock) {
        this.lock = lock;
    }

    @Override
    public void save(TaskStageRecord taskStageRecord) {
        withWriteLock(() -> {
            var record = Objects.requireNonNull(taskStageRecord, "taskStageRecord");
            stagesByTask.computeIfAbsent(record.taskId(), ignored -> new ConcurrentHashMap<>())
                .put(record.stage().name(), record);
        });
    }

    @Override
    public List<TaskStageRecord> listByTask(String taskId) {
        return withReadLock(() -> stagesByTask.getOrDefault(Objects.requireNonNull(taskId, "taskId"), new ConcurrentHashMap<>())
            .values()
            .stream()
            .sorted(java.util.Comparator.comparingInt(TaskStageRecord::sequence))
            .toList());
    }

    @Override
    public void deleteByTask(String taskId) {
        withWriteLock(() -> stagesByTask.remove(Objects.requireNonNull(taskId, "taskId")));
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
