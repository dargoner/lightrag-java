package io.github.lightrag.demo;

import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.RawDocumentSource;
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

    private final LightRag lightRag;
    private final ExecutorService executor;
    private final ConcurrentMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    IngestJobService(LightRag lightRag) {
        this.lightRag = lightRag;
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
        return createDocumentJob(workspaceId, documents, async, null, 1).jobId();
    }

    String submitSources(
        String workspaceId,
        List<RawDocumentSource> sources,
        DocumentIngestOptions options,
        boolean async
    ) {
        return createSourceJob(workspaceId, sources, options, async, null, 1).jobId();
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
        if (!original.isRetriable()) {
            throw new JobConflictException("job is not retriable: " + jobId);
        }
        if (original.hasRawSources()) {
            var retrySources = original.rawSources().stream()
                .filter(source -> shouldRetryDocument(workspaceId, source.sourceId()))
                .toList();
            if (retrySources.isEmpty()) {
                throw new JobConflictException("job has no retryable documents: " + jobId);
            }
            return createSourceJob(
                workspaceId,
                retrySources,
                original.ingestOptions(),
                async,
                jobId,
                original.attempt() + 1
            ).toSnapshot();
        }
        var retryDocuments = original.documents().stream()
            .filter(document -> shouldRetryDocument(workspaceId, document.id()))
            .toList();
        if (retryDocuments.isEmpty()) {
            throw new JobConflictException("job has no retryable documents: " + jobId);
        }
        return createDocumentJob(workspaceId, retryDocuments, async, jobId, original.attempt() + 1).toSnapshot();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private JobState createDocumentJob(
        String workspaceId,
        List<Document> documents,
        boolean async,
        String retriedFromJobId,
        int attempt
    ) {
        var copiedDocuments = List.copyOf(Objects.requireNonNull(documents, "documents"));
        return createJob(
            workspaceId,
            copiedDocuments,
            List.of(),
            null,
            copiedDocuments.size(),
            async,
            retriedFromJobId,
            attempt
        );
    }

    private JobState createSourceJob(
        String workspaceId,
        List<RawDocumentSource> sources,
        DocumentIngestOptions options,
        boolean async,
        String retriedFromJobId,
        int attempt
    ) {
        var copiedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        return createJob(
            workspaceId,
            List.of(),
            copiedSources,
            Objects.requireNonNull(options, "options"),
            copiedSources.size(),
            async,
            retriedFromJobId,
            attempt
        );
    }

    private JobState createJob(
        String workspaceId,
        List<Document> documents,
        List<RawDocumentSource> rawSources,
        DocumentIngestOptions ingestOptions,
        int documentCount,
        boolean async,
        String retriedFromJobId,
        int attempt
    ) {
        var normalizedWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        var jobId = UUID.randomUUID().toString();
        var jobState = new JobState(
            jobId,
            normalizedWorkspaceId,
            documents,
            rawSources,
            ingestOptions,
            documentCount,
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
            rethrowJobFailure(jobState.failureCause());
        }
        return jobState;
    }

    private JobState requireJob(String workspaceId, String jobId) {
        var targetWorkspaceId = requireNonBlank(workspaceId, "workspaceId");
        return Optional.ofNullable(jobs.get(jobId))
            .filter(jobState -> jobState.workspaceId().equals(targetWorkspaceId))
            .orElseThrow(() -> new NoSuchElementException("job not found: " + jobId));
    }

    private boolean shouldRetryDocument(String workspaceId, String documentId) {
        try {
            var status = lightRag.getDocumentStatus(workspaceId, documentId);
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
            if (jobState.hasRawSources()) {
                lightRag.ingestSources(jobState.workspaceId(), jobState.rawSources(), jobState.ingestOptions());
            } else {
                lightRag.ingest(jobState.workspaceId(), jobState.documents());
            }
            jobState.markSucceeded();
        } catch (Throwable throwable) {
            if (jobState.isCancelling()) {
                jobState.markCancelled(JOB_CANCELLED_MESSAGE);
                Thread.currentThread().interrupt();
                return;
            }
            jobState.markFailed(throwable);
        }
    }

    private static void rethrowJobFailure(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("ingest job failed", failure);
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
        private final List<RawDocumentSource> rawSources;
        private final DocumentIngestOptions ingestOptions;
        private final int documentCount;
        private final long sequence;
        private final Instant createdAt;
        private final String retriedFromJobId;
        private final int attempt;
        private IngestJobStatus status = IngestJobStatus.PENDING;
        private Instant startedAt;
        private Instant finishedAt;
        private String errorMessage;
        private Throwable failureCause;
        private FutureTask<Void> futureTask;

        JobState(
            String jobId,
            String workspaceId,
            List<Document> documents,
            List<RawDocumentSource> rawSources,
            DocumentIngestOptions ingestOptions,
            int documentCount,
            long sequence,
            Instant createdAt,
            String retriedFromJobId,
            int attempt
        ) {
            this.jobId = jobId;
            this.workspaceId = workspaceId;
            this.documents = documents;
            this.rawSources = rawSources;
            this.ingestOptions = ingestOptions;
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

        List<RawDocumentSource> rawSources() {
            return rawSources;
        }

        DocumentIngestOptions ingestOptions() {
            return ingestOptions;
        }

        boolean hasRawSources() {
            return !rawSources.isEmpty();
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
            failureCause = null;
            finishedAt = Instant.now();
        }

        synchronized void markFailed(Throwable failure) {
            status = IngestJobStatus.FAILED;
            errorMessage = normalizeMessage(failure == null ? null : failure.getMessage());
            failureCause = failure;
            finishedAt = Instant.now();
        }

        synchronized void markCancelled(String message) {
            status = IngestJobStatus.CANCELLED;
            errorMessage = normalizeMessage(message);
            failureCause = null;
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

        synchronized Throwable failureCause() {
            return failureCause;
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
