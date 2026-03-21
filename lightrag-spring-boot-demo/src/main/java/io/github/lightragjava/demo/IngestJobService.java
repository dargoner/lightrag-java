package io.github.lightragjava.demo;

import io.github.lightragjava.api.DocumentStatus;
import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import io.github.lightragjava.types.Document;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

@Service
class IngestJobService {
    private static final String JOB_CANCELLED_MESSAGE = "job cancelled";

    private final WorkspaceLightRagFactory workspaceLightRagFactory;
    private final ExecutorService executor;
    private final ConcurrentMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    IngestJobService(WorkspaceLightRagFactory workspaceLightRagFactory) {
        this.workspaceLightRagFactory = workspaceLightRagFactory;
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                var thread = new Thread(runnable, "ingest-job-service");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    String submit(String workspaceId, List<Document> documents, boolean async) {
        return createJob(workspaceId, documents, async, null, 1).jobId();
    }

    Optional<JobSnapshot> getJob(String workspaceId, String jobId) {
        var targetWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        return Optional.ofNullable(jobs.get(jobId))
            .filter(jobState -> jobState.workspaceId().equals(targetWorkspaceId))
            .map(JobState::toSnapshot);
    }

    JobPage listJobs(String workspaceId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
        var targetWorkspaceId = requireNonBlank(workspaceId, "workspaceId");

        var snapshots = jobs.values().stream()
            .filter(jobState -> jobState.workspaceId().equals(targetWorkspaceId))
            .sorted((left, right) -> Long.compare(right.sequence(), left.sequence()))
            .map(JobState::toSnapshot)
            .toList();
        var fromIndex = Math.min(page * size, snapshots.size());
        var toIndex = Math.min(fromIndex + size, snapshots.size());
        return new JobPage(snapshots.subList(fromIndex, toIndex), page, size, snapshots.size());
    }

    JobSnapshot cancel(String workspaceId, String jobId) {
        return requireJob(workspaceId, jobId).cancel();
    }

    JobSnapshot retry(String workspaceId, String jobId, boolean async) {
        var original = requireJob(workspaceId, jobId);
        var retryDocuments = retryableDocuments(original);
        if (retryDocuments.isEmpty()) {
            throw new JobConflictException("job has no retryable documents: " + jobId);
        }
        return createJob(workspaceId, retryDocuments, async, jobId, original.attempt() + 1).toSnapshot();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private JobState createJob(
        String workspaceId,
        List<Document> documents,
        boolean async,
        String retriedFromJobId,
        int attempt
    ) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var copiedDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        var jobId = UUID.randomUUID().toString();
        var jobState = new JobState(
            jobId,
            normalizedWorkspaceId,
            copiedDocuments,
            copiedDocuments.size(),
            sequence.incrementAndGet(),
            Instant.now(),
            retriedFromJobId,
            attempt
        );
        jobs.put(jobId, jobState);
        if (async) {
            var futureTask = new FutureTask<Void>(() -> {
                runJob(jobState);
                return null;
            });
            jobState.attachFuture(futureTask);
            executor.execute(futureTask);
        } else {
            runJob(jobState);
        }
        return jobState;
    }

    private JobState requireJob(String workspaceId, String jobId) {
        var targetWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        return Optional.ofNullable(jobs.get(jobId))
            .filter(jobState -> jobState.workspaceId().equals(targetWorkspaceId))
            .orElseThrow(() -> new NoSuchElementException("job not found: " + jobId));
    }

    private List<Document> retryableDocuments(JobState jobState) {
        if (!jobState.isRetriable()) {
            throw new JobConflictException("job is not retriable: " + jobState.jobId());
        }
        return jobState.documents().stream()
            .filter(document -> shouldRetryDocument(jobState.workspaceId(), document))
            .toList();
    }

    private boolean shouldRetryDocument(String workspaceId, Document document) {
        try {
            var status = workspaceLightRagFactory.get(workspaceId).getDocumentStatus(document.id());
            return status == null || status.status() != DocumentStatus.PROCESSED;
        } catch (NoSuchElementException ignored) {
            return true;
        }
    }

    private void runJob(JobState jobState) {
        if (!jobState.markStarted()) {
            return;
        }
        try {
            workspaceLightRagFactory.get(jobState.workspaceId()).ingest(jobState.documents());
            jobState.markSucceeded();
        } catch (Throwable throwable) {
            if (jobState.isCancelling()) {
                jobState.markCancelled(JOB_CANCELLED_MESSAGE);
                Thread.currentThread().interrupt();
                return;
            }
            jobState.markFailed(throwable.getMessage());
        }
    }

    record JobSnapshot(
        String jobId,
        IngestJobStatus status,
        int documentCount,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        boolean cancellable,
        boolean retriable,
        String retriedFromJobId,
        int attempt
    ) {
    }

    record JobPage(List<JobSnapshot> items, int page, int size, int total) {
    }

    enum IngestJobStatus {
        PENDING,
        RUNNING,
        CANCELLING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    static final class JobConflictException extends RuntimeException {
        JobConflictException(String message) {
            super(message);
        }
    }

    private static final class JobState {
        private final String jobId;
        private final String workspaceId;
        private final List<Document> documents;
        private final int documentCount;
        private final long sequence;
        private final Instant createdAt;
        private final String retriedFromJobId;
        private final int attempt;
        private IngestJobStatus status = IngestJobStatus.PENDING;
        private Instant startedAt;
        private Instant finishedAt;
        private String errorMessage;
        private FutureTask<Void> futureTask;

        JobState(
            String jobId,
            String workspaceId,
            List<Document> documents,
            int documentCount,
            long sequence,
            Instant createdAt,
            String retriedFromJobId,
            int attempt
        ) {
            this.jobId = jobId;
            this.workspaceId = workspaceId;
            this.documents = documents;
            this.documentCount = documentCount;
            this.sequence = sequence;
            this.createdAt = createdAt;
            this.retriedFromJobId = retriedFromJobId;
            this.attempt = attempt;
        }

        String jobId() {
            return jobId;
        }

        String workspaceId() {
            return workspaceId;
        }

        List<Document> documents() {
            return documents;
        }

        long sequence() {
            return sequence;
        }

        int attempt() {
            return attempt;
        }

        synchronized void attachFuture(FutureTask<Void> futureTask) {
            this.futureTask = futureTask;
        }

        synchronized boolean markStarted() {
            if (status != IngestJobStatus.PENDING) {
                return false;
            }
            startedAt = Instant.now();
            status = IngestJobStatus.RUNNING;
            errorMessage = null;
            return true;
        }

        synchronized void markSucceeded() {
            status = IngestJobStatus.SUCCEEDED;
            errorMessage = null;
            finishedAt = Instant.now();
        }

        synchronized void markFailed(String message) {
            status = IngestJobStatus.FAILED;
            errorMessage = normalizeMessage(message);
            finishedAt = Instant.now();
        }

        synchronized void markCancelled(String message) {
            status = IngestJobStatus.CANCELLED;
            errorMessage = normalizeMessage(message);
            finishedAt = Instant.now();
        }

        synchronized boolean isRetriable() {
            return status == IngestJobStatus.FAILED || status == IngestJobStatus.CANCELLED;
        }

        synchronized boolean isCancelling() {
            return status == IngestJobStatus.CANCELLING;
        }

        synchronized JobSnapshot cancel() {
            switch (status) {
                case PENDING -> {
                    status = IngestJobStatus.CANCELLED;
                    errorMessage = JOB_CANCELLED_MESSAGE;
                    finishedAt = Instant.now();
                    if (futureTask != null) {
                        futureTask.cancel(false);
                    }
                    return toSnapshot();
                }
                case RUNNING -> {
                    status = IngestJobStatus.CANCELLING;
                    errorMessage = JOB_CANCELLED_MESSAGE;
                    if (futureTask != null) {
                        futureTask.cancel(true);
                    }
                    return toSnapshot();
                }
                case CANCELLING -> {
                    return toSnapshot();
                }
                default -> throw new JobConflictException("job is not cancellable: " + jobId);
            }
        }

        synchronized JobSnapshot toSnapshot() {
            return new JobSnapshot(
                jobId,
                status,
                documentCount,
                createdAt,
                startedAt,
                finishedAt,
                errorMessage,
                status == IngestJobStatus.PENDING || status == IngestJobStatus.RUNNING,
                isRetriable(),
                retriedFromJobId,
                attempt
            );
        }

        private static String normalizeMessage(String message) {
            return message == null ? null : message.strip();
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        var normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
