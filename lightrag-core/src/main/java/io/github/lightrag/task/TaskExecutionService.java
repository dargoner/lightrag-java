package io.github.lightrag.task;

import io.github.lightrag.api.TaskEvent;
import io.github.lightrag.api.TaskEventListener;
import io.github.lightrag.api.TaskEventScope;
import io.github.lightrag.api.TaskEventType;
import io.github.lightrag.api.TaskSnapshot;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageSnapshot;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.indexing.IndexingProgressListener;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private final Function<String, AtomicStorageProvider> providerResolver;
    private final TaskEventPublisher eventPublisher;
    private final ExecutorService executor;
    private final ConcurrentMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final Set<String> recoveredWorkspaces = ConcurrentHashMap.newKeySet();

    public TaskExecutionService(Function<String, AtomicStorageProvider> providerResolver) {
        this(providerResolver, List.of());
    }

    public TaskExecutionService(
        Function<String, AtomicStorageProvider> providerResolver,
        List<TaskEventListener> listeners
    ) {
        this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
        this.eventPublisher = new TaskEventPublisher(Objects.requireNonNull(listeners, "listeners"));
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
        return submit(workspaceId, taskType, metadata, List.of(), work);
    }

    public String submit(
        String workspaceId,
        TaskType taskType,
        Map<String, String> metadata,
        List<TaskEventListener> listeners,
        TaskWork work
    ) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var provider = providerResolver.apply(normalizedWorkspaceId);
        recoverInterruptedTasks(normalizedWorkspaceId, provider);
        var taskId = UUID.randomUUID().toString();
        var requestedAt = Instant.now();
        var taskListeners = mergeListeners(listeners);
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
        eventPublisher.publish(taskEvent(
            taskId,
            normalizedWorkspaceId,
            taskType,
            TaskEventType.TASK_SUBMITTED,
            "pending",
            "queued",
            metadata
        ));
        if (!taskListeners.isEmpty()) {
            new TaskEventPublisher(taskListeners).publish(taskEvent(
                taskId,
                normalizedWorkspaceId,
                taskType,
                TaskEventType.TASK_SUBMITTED,
                "pending",
                "queued",
                metadata
            ));
        }
        var future = executor.submit(() -> runTask(
            normalizedWorkspaceId,
            provider,
            taskId,
            taskType,
            requestedAt,
            Map.copyOf(metadata),
            taskListeners,
            Objects.requireNonNull(work, "work")
        ));
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

    private void runTask(
        String workspaceId,
        AtomicStorageProvider provider,
        String taskId,
        TaskType taskType,
        Instant requestedAt,
        Map<String, String> metadata,
        List<TaskEventListener> listeners,
        TaskWork work
    ) {
        var reporter = new TaskReporter(
            provider,
            workspaceId,
            taskId,
            taskType,
            metadata,
            combinePublishers(eventPublisher, listeners)
        );
        var startedAt = Instant.now();
        var queueWaitMs = Math.max(0L, Duration.between(requestedAt, startedAt).toMillis());
        reporter.updateMetadata(Map.of("queueWaitMs", Long.toString(queueWaitMs)));
        log.info("task_event=PERF taskId={} workspaceId={} scope=task phase=queue_wait durationMs={}",
            taskId, workspaceId, queueWaitMs);
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
            var totalDurationMs = Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
            reporter.updateMetadata(Map.of("totalDurationMs", Long.toString(totalDurationMs)));
            log.info("task_event=PERF taskId={} workspaceId={} scope=task phase=total durationMs={}",
                taskId, workspaceId, totalDurationMs);
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

    private TaskEvent taskEvent(
        String taskId,
        String workspaceId,
        TaskType taskType,
        TaskEventType eventType,
        String status,
        String message,
        Map<String, String> attributes
    ) {
        return new TaskEvent(
            UUID.randomUUID().toString(),
            eventType,
            TaskEventScope.TASK,
            Instant.now(),
            workspaceId,
            taskId,
            taskType,
            null,
            null,
            null,
            status,
            message,
            Map.copyOf(attributes)
        );
    }

    @FunctionalInterface
    public interface TaskWork {
        void run(IndexingProgressListener progressListener);
    }

    private List<TaskEventListener> mergeListeners(List<TaskEventListener> listeners) {
        return List.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    private static TaskEventPublisher combinePublishers(
        TaskEventPublisher globalPublisher,
        List<TaskEventListener> listeners
    ) {
        if (listeners.isEmpty()) {
            return globalPublisher;
        }
        var combined = new java.util.ArrayList<TaskEventListener>();
        combined.addAll(listeners);
        return new TaskEventPublisher(combined) {
            @Override
            void publish(TaskEvent event) {
                globalPublisher.publish(event);
                super.publish(event);
            }
        };
    }

    private static final class TaskReporter implements IndexingProgressListener, TaskMetadataReporter {
        private final AtomicStorageProvider provider;
        private final String workspaceId;
        private final String taskId;
        private final TaskType taskType;
        private volatile TaskStage currentStage;
        private final Map<String, String> initialMetadata;
        private final TaskEventPublisher eventPublisher;

        private TaskReporter(
            AtomicStorageProvider provider,
            String workspaceId,
            String taskId,
            TaskType taskType,
            Map<String, String> initialMetadata,
            TaskEventPublisher eventPublisher
        ) {
            this.provider = Objects.requireNonNull(provider, "provider");
            this.workspaceId = workspaceId;
            this.taskId = taskId;
            this.taskType = Objects.requireNonNull(taskType, "taskType");
            this.initialMetadata = Map.copyOf(Objects.requireNonNull(initialMetadata, "initialMetadata"));
            this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
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
            eventPublisher.publish(stageEvent(stage, TaskEventType.STAGE_STARTED, "running", message));
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
            eventPublisher.publish(stageEvent(stage, TaskEventType.STAGE_SUCCEEDED, "succeeded", message));
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
            eventPublisher.publish(stageEvent(stage, TaskEventType.STAGE_SKIPPED, "skipped", message));
        }

        @Override
        public void onDocumentStarted(String documentId, String message) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                DocumentStatus.PROCESSING,
                existing.chunkCount(),
                existing.entityCount(),
                existing.relationCount(),
                existing.chunkVectorCount(),
                existing.entityVectorCount(),
                existing.relationVectorCount(),
                null
            ));
            eventPublisher.publish(documentEvent(documentId, TaskEventType.DOCUMENT_STARTED, message, Map.of()));
        }

        @Override
        public void onDocumentChunked(String documentId, int chunkCount, String message) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                existing.status(),
                chunkCount,
                existing.entityCount(),
                existing.relationCount(),
                existing.chunkVectorCount(),
                existing.entityVectorCount(),
                existing.relationVectorCount(),
                existing.errorMessage()
            ));
            eventPublisher.publish(documentEvent(
                documentId,
                TaskEventType.DOCUMENT_CHUNKED,
                message,
                Map.of("chunkCount", Integer.toString(chunkCount))
            ));
        }

        @Override
        public void onDocumentGraphReady(String documentId, int entityCount, int relationCount, String message) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                existing.status(),
                existing.chunkCount(),
                entityCount,
                relationCount,
                existing.chunkVectorCount(),
                existing.entityVectorCount(),
                existing.relationVectorCount(),
                existing.errorMessage()
            ));
            eventPublisher.publish(documentEvent(
                documentId,
                TaskEventType.DOCUMENT_GRAPH_READY,
                message,
                Map.of(
                    "entityCount", Integer.toString(entityCount),
                    "relationCount", Integer.toString(relationCount)
                )
            ));
        }

        @Override
        public void onDocumentVectorsReady(
            String documentId,
            int chunkVectorCount,
            int entityVectorCount,
            int relationVectorCount,
            String message
        ) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                existing.status(),
                existing.chunkCount(),
                existing.entityCount(),
                existing.relationCount(),
                chunkVectorCount,
                entityVectorCount,
                relationVectorCount,
                existing.errorMessage()
            ));
            eventPublisher.publish(documentEvent(
                documentId,
                TaskEventType.DOCUMENT_VECTORS_READY,
                message,
                Map.of(
                    "chunkVectorCount", Integer.toString(chunkVectorCount),
                    "entityVectorCount", Integer.toString(entityVectorCount),
                    "relationVectorCount", Integer.toString(relationVectorCount)
                )
            ));
        }

        @Override
        public void onDocumentCommitted(String documentId, String message) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                DocumentStatus.PROCESSED,
                existing.chunkCount(),
                existing.entityCount(),
                existing.relationCount(),
                existing.chunkVectorCount(),
                existing.entityVectorCount(),
                existing.relationVectorCount(),
                null
            ));
            eventPublisher.publish(documentEvent(documentId, TaskEventType.DOCUMENT_COMMITTED, message, Map.of()));
        }

        @Override
        public void onDocumentFailed(String documentId, String message) {
            upsertDocumentRecord(documentId, existing -> new TaskDocumentStore.TaskDocumentRecord(
                taskId,
                documentId,
                DocumentStatus.FAILED,
                existing.chunkCount(),
                existing.entityCount(),
                existing.relationCount(),
                existing.chunkVectorCount(),
                existing.entityVectorCount(),
                existing.relationVectorCount(),
                message
            ));
            eventPublisher.publish(documentEvent(documentId, TaskEventType.DOCUMENT_FAILED, message, Map.of()));
        }

        private void markRunning(String summary) {
            var current = loadTask();
            provider.taskStore().save(copyTask(current, TaskStatus.RUNNING, Instant.now(), null, summary, null, current.cancelRequested()));
            eventPublisher.publish(taskEvent(TaskEventType.TASK_RUNNING, "running", summary));
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
            eventPublisher.publish(taskEvent(TaskEventType.TASK_SUCCEEDED, "succeeded", summary));
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
            eventPublisher.publish(taskEvent(TaskEventType.TASK_CANCELLED, "cancelled", message));
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
            eventPublisher.publish(taskEvent(TaskEventType.TASK_FAILED, "failed", failure.getMessage()));
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

        private void upsertDocumentRecord(
            String documentId,
            Function<TaskDocumentStore.TaskDocumentRecord, TaskDocumentStore.TaskDocumentRecord> updater
        ) {
            var taskDocumentStore = loadTaskDocumentStore();
            if (taskDocumentStore == null) {
                return;
            }
            var current = taskDocumentStore.load(taskId, documentId)
                .orElseGet(() -> new TaskDocumentStore.TaskDocumentRecord(
                    taskId,
                    documentId,
                    DocumentStatus.PROCESSING,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null
                ));
            taskDocumentStore.save(Objects.requireNonNull(updater, "updater").apply(current));
        }

        private TaskDocumentStore loadTaskDocumentStore() {
            try {
                return provider.taskDocumentStore();
            } catch (UnsupportedOperationException exception) {
                if (StorageProvider.TASK_DOCUMENT_STORE_UNSUPPORTED_MESSAGE.equals(exception.getMessage())
                    || "taskDocumentStore is not available in this relational adapter".equals(exception.getMessage())) {
                    return null;
                }
                throw exception;
            }
        }

        private TaskStageStore.TaskStageRecord existingStage(TaskStage stage) {
            return provider.taskStageStore().listByTask(taskId).stream()
                .filter(record -> record.stage() == stage)
                .findFirst()
                .orElse(null);
        }

        private TaskEvent stageEvent(TaskStage stage, TaskEventType eventType, String status, String message) {
            return new TaskEvent(
                UUID.randomUUID().toString(),
                eventType,
                TaskEventScope.STAGE,
                Instant.now(),
                workspaceId,
                taskId,
                taskType,
                null,
                null,
                stage,
                status,
                message,
                loadMetadata()
            );
        }

        private TaskEvent taskEvent(TaskEventType eventType, String status, String message) {
            return new TaskEvent(
                UUID.randomUUID().toString(),
                eventType,
                TaskEventScope.TASK,
                Instant.now(),
                workspaceId,
                taskId,
                taskType,
                null,
                null,
                null,
                status,
                message,
                loadMetadata()
            );
        }

        private TaskEvent documentEvent(
            String documentId,
            TaskEventType eventType,
            String message,
            Map<String, String> extraAttributes
        ) {
            var attributes = new LinkedHashMap<String, String>(loadMetadata());
            attributes.putAll(extraAttributes);
            return new TaskEvent(
                UUID.randomUUID().toString(),
                eventType,
                TaskEventScope.DOCUMENT,
                Instant.now(),
                workspaceId,
                taskId,
                taskType,
                documentId,
                null,
                null,
                "running",
                message,
                attributes
            );
        }

        private Map<String, String> loadMetadata() {
            var task = provider.taskStore().load(taskId).orElse(null);
            if (task == null) {
                return initialMetadata;
            }
            return new LinkedHashMap<>(task.metadata());
        }
    }
}
