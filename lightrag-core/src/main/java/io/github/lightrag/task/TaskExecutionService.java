package io.github.lightrag.task;

import io.github.lightrag.api.TaskSnapshot;
import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageSnapshot;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.indexing.IndexingProgressListener;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class TaskExecutionService implements AutoCloseable {
    private static final String INTERRUPTED_MESSAGE = "task interrupted before completion";

    private final Function<String, AtomicStorageProvider> providerResolver;
    private final ExecutorService executor;
    private final ConcurrentMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final Set<String> recoveredWorkspaces = ConcurrentHashMap.newKeySet();

    public TaskExecutionService(Function<String, AtomicStorageProvider> providerResolver) {
        this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
        var sequence = new AtomicLong();
        this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                var thread = new Thread(runnable, "lightrag-task-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public String submit(String workspaceId, TaskType taskType, Map<String, String> metadata, TaskWork work) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var provider = providerResolver.apply(normalizedWorkspaceId);
        recoverInterruptedTasks(normalizedWorkspaceId, provider);
        var taskId = UUID.randomUUID().toString();
        var requestedAt = Instant.now();
        provider.taskStore().save(new TaskStore.TaskRecord(
            taskId,
            normalizedWorkspaceId,
            Objects.requireNonNull(taskType, "taskType"),
            TaskStatus.PENDING,
            requestedAt,
            null,
            null,
            "queued",
            null,
            false,
            Map.copyOf(Objects.requireNonNull(metadata, "metadata"))
        ));
        var future = executor.submit(() -> runTask(normalizedWorkspaceId, provider, taskId, Objects.requireNonNull(work, "work")));
        runningTasks.put(taskKey(normalizedWorkspaceId, taskId), future);
        return taskId;
    }

    public TaskSnapshot getTask(String workspaceId, String taskId) {
        var provider = providerResolver.apply(requireNonBlank(workspaceId, "workspaceId"));
        recoverInterruptedTasks(workspaceId, provider);
        var record = provider.taskStore().load(requireNonBlank(taskId, "taskId"))
            .orElseThrow(() -> new NoSuchElementException("task does not exist: " + taskId));
        return toSnapshot(provider, record);
    }

    public List<TaskSnapshot> listTasks(String workspaceId) {
        var provider = providerResolver.apply(requireNonBlank(workspaceId, "workspaceId"));
        recoverInterruptedTasks(workspaceId, provider);
        return provider.taskStore().list().stream()
            .map(record -> toSnapshot(provider, record))
            .toList();
    }

    public TaskSnapshot cancel(String workspaceId, String taskId) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var normalizedTaskId = requireNonBlank(taskId, "taskId");
        var provider = providerResolver.apply(normalizedWorkspaceId);
        recoverInterruptedTasks(normalizedWorkspaceId, provider);
        var current = provider.taskStore().load(normalizedTaskId)
            .orElseThrow(() -> new NoSuchElementException("task does not exist: " + normalizedTaskId));
        if (current.status().isTerminal()) {
            return toSnapshot(provider, current);
        }
        provider.taskStore().save(copyTask(
            current,
            current.status() == TaskStatus.PENDING ? TaskStatus.CANCELLED : current.status(),
            current.startedAt(),
            current.status() == TaskStatus.PENDING ? Instant.now() : current.finishedAt(),
            current.status() == TaskStatus.PENDING ? "cancelled" : current.summary(),
            current.errorMessage(),
            true
        ));
        if (current.status() == TaskStatus.PENDING) {
            provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                normalizedTaskId,
                TaskStage.COMPLETED,
                TaskStageStatus.SKIPPED,
                sequence(TaskStage.COMPLETED),
                Instant.now(),
                Instant.now(),
                "task cancelled before execution",
                null
            ));
        }
        var future = runningTasks.get(taskKey(normalizedWorkspaceId, normalizedTaskId));
        if (future != null) {
            future.cancel(true);
        }
        return getTask(normalizedWorkspaceId, normalizedTaskId);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void runTask(String workspaceId, AtomicStorageProvider provider, String taskId, TaskWork work) {
        var reporter = new TaskReporter(provider, workspaceId, taskId);
        try {
            reporter.markRunning("running");
            reporter.onStageStarted(TaskStage.PREPARING, "starting task");
            reporter.onStageSucceeded(TaskStage.PREPARING, "task started");
            work.run(reporter);
            reporter.complete("completed");
        } catch (TaskCancelledException exception) {
            reporter.cancel(exception.getMessage());
        } catch (Throwable failure) {
            reporter.fail(failure);
        } finally {
            runningTasks.remove(taskKey(workspaceId, taskId));
        }
    }

    private void recoverInterruptedTasks(String workspaceId, AtomicStorageProvider provider) {
        if (!recoveredWorkspaces.add(workspaceId)) {
            return;
        }
        for (var record : provider.taskStore().list()) {
            if (record.status().isTerminal()) {
                continue;
            }
            provider.taskStore().save(copyTask(
                record,
                TaskStatus.FAILED,
                record.startedAt(),
                Instant.now(),
                record.summary(),
                INTERRUPTED_MESSAGE,
                record.cancelRequested()
            ));
            var stages = provider.taskStageStore().listByTask(record.taskId());
            if (!stages.isEmpty()) {
                var last = stages.get(stages.size() - 1);
                if (last.status() == TaskStageStatus.RUNNING || last.status() == TaskStageStatus.PENDING) {
                    provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                        last.taskId(),
                        last.stage(),
                        TaskStageStatus.FAILED,
                        last.sequence(),
                        last.startedAt(),
                        Instant.now(),
                        last.message(),
                        INTERRUPTED_MESSAGE
                    ));
                }
            }
        }
    }

    private static TaskSnapshot toSnapshot(AtomicStorageProvider provider, TaskStore.TaskRecord record) {
        return new TaskSnapshot(
            record.taskId(),
            record.workspaceId(),
            record.taskType(),
            record.status(),
            record.requestedAt(),
            record.startedAt(),
            record.finishedAt(),
            record.summary(),
            record.errorMessage(),
            record.cancelRequested(),
            record.metadata(),
            provider.taskStageStore().listByTask(record.taskId()).stream()
                .map(stage -> new TaskStageSnapshot(
                    stage.stage(),
                    stage.status(),
                    stage.sequence(),
                    stage.startedAt(),
                    stage.finishedAt(),
                    stage.message(),
                    stage.errorMessage()
                ))
                .toList()
        );
    }

    private static TaskStore.TaskRecord copyTask(
        TaskStore.TaskRecord source,
        TaskStatus status,
        Instant startedAt,
        Instant finishedAt,
        String summary,
        String errorMessage,
        boolean cancelRequested
    ) {
        return new TaskStore.TaskRecord(
            source.taskId(),
            source.workspaceId(),
            source.taskType(),
            status,
            source.requestedAt(),
            startedAt,
            finishedAt,
            summary,
            errorMessage,
            cancelRequested,
            source.metadata()
        );
    }

    private static int sequence(TaskStage stage) {
        return stage.ordinal() + 1;
    }

    private static String taskKey(String workspaceId, String taskId) {
        return workspaceId + "::" + taskId;
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    @FunctionalInterface
    public interface TaskWork {
        void run(IndexingProgressListener progressListener);
    }

    private static final class TaskReporter implements IndexingProgressListener, TaskMetadataReporter {
        private final AtomicStorageProvider provider;
        private final String workspaceId;
        private final String taskId;
        private volatile TaskStage currentStage;

        private TaskReporter(AtomicStorageProvider provider, String workspaceId, String taskId) {
            this.provider = Objects.requireNonNull(provider, "provider");
            this.workspaceId = workspaceId;
            this.taskId = taskId;
        }

        @Override
        public void onStageStarted(TaskStage stage, String message) {
            checkCancelled();
            currentStage = Objects.requireNonNull(stage, "stage");
            var existing = existingStage(stage);
            provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                taskId,
                stage,
                TaskStageStatus.RUNNING,
                sequence(stage),
                existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt(),
                null,
                message,
                null
            ));
        }

        @Override
        public void onStageSucceeded(TaskStage stage, String message) {
            checkCancelled();
            var existing = existingStage(stage);
            provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                taskId,
                stage,
                TaskStageStatus.SUCCEEDED,
                sequence(stage),
                existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt(),
                Instant.now(),
                message,
                null
            ));
        }

        @Override
        public void onStageSkipped(TaskStage stage, String message) {
            provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                taskId,
                stage,
                TaskStageStatus.SKIPPED,
                sequence(stage),
                Instant.now(),
                Instant.now(),
                message,
                null
            ));
        }

        private void markRunning(String summary) {
            var current = loadTask();
            provider.taskStore().save(copyTask(current, TaskStatus.RUNNING, Instant.now(), null, summary, null, current.cancelRequested()));
        }

        @Override
        public void updateMetadata(Map<String, String> metadata) {
            var additions = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
            if (additions.isEmpty()) {
                return;
            }
            var current = loadTask();
            var merged = new java.util.LinkedHashMap<String, String>(current.metadata());
            merged.putAll(additions);
            provider.taskStore().save(new TaskStore.TaskRecord(
                current.taskId(),
                current.workspaceId(),
                current.taskType(),
                current.status(),
                current.requestedAt(),
                current.startedAt(),
                current.finishedAt(),
                current.summary(),
                current.errorMessage(),
                current.cancelRequested(),
                Map.copyOf(merged)
            ));
        }

        private void complete(String summary) {
            onStageStarted(TaskStage.COMPLETED, "finalizing task");
            onStageSucceeded(TaskStage.COMPLETED, summary);
            var current = loadTask();
            provider.taskStore().save(copyTask(current, TaskStatus.SUCCEEDED, current.startedAt(), Instant.now(), summary, null, current.cancelRequested()));
        }

        private void cancel(String message) {
            var current = loadTask();
            if (currentStage != null) {
                var existing = existingStage(currentStage);
                provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                    taskId,
                    currentStage,
                    TaskStageStatus.SKIPPED,
                    sequence(currentStage),
                    existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt(),
                    Instant.now(),
                    existing == null ? message : existing.message(),
                    null
                ));
            }
            provider.taskStore().save(copyTask(current, TaskStatus.CANCELLED, current.startedAt(), Instant.now(), "cancelled", message, true));
        }

        private void fail(Throwable failure) {
            var current = loadTask();
            if (currentStage != null) {
                var existing = existingStage(currentStage);
                provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
                    taskId,
                    currentStage,
                    TaskStageStatus.FAILED,
                    sequence(currentStage),
                    existing == null || existing.startedAt() == null ? Instant.now() : existing.startedAt(),
                    Instant.now(),
                    existing == null ? "" : existing.message(),
                    failure.getMessage()
                ));
            }
            provider.taskStore().save(copyTask(
                current,
                TaskStatus.FAILED,
                current.startedAt(),
                Instant.now(),
                current.summary(),
                failure.getMessage(),
                current.cancelRequested()
            ));
        }

        private void checkCancelled() {
            if (Thread.currentThread().isInterrupted()) {
                throw new TaskCancelledException("task cancelled");
            }
            var current = loadTask();
            if (current.cancelRequested()) {
                throw new TaskCancelledException("task cancelled");
            }
        }

        private TaskStore.TaskRecord loadTask() {
            return provider.taskStore().load(taskId)
                .orElseThrow(() -> new NoSuchElementException("task does not exist: " + taskId));
        }

        private TaskStageStore.TaskStageRecord existingStage(TaskStage stage) {
            return provider.taskStageStore().listByTask(taskId).stream()
                .filter(record -> record.stage() == stage)
                .findFirst()
                .orElse(null);
        }
    }
}
