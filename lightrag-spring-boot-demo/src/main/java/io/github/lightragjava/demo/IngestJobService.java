package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import io.github.lightragjava.types.Document;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
class IngestJobService {
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
        var jobId = UUID.randomUUID().toString();
        var jobState = new JobState(
            jobId,
            requireNonBlank(workspaceId, "workspaceId"),
            documents.size(),
            sequence.incrementAndGet(),
            Instant.now()
        );
        jobs.put(jobId, jobState);
        if (async) {
            executor.submit(() -> runJob(jobState, documents));
        } else {
            runJob(jobState, documents);
        }
        return jobId;
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runJob(JobState jobState, List<Document> documents) {
        jobState.markStarted();
        try {
            workspaceLightRagFactory.get(jobState.workspaceId()).ingest(documents);
            jobState.markFinished(IngestJobStatus.SUCCEEDED);
        } catch (Throwable throwable) {
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
        String errorMessage
    ) {
    }

    record JobPage(List<JobSnapshot> items, int page, int size, int total) {
    }

    enum IngestJobStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private static final class JobState {
        private final String jobId;
        private final String workspaceId;
        private final int documentCount;
        private final long sequence;
        private final Instant createdAt;
        private final AtomicReference<IngestJobStatus> status = new AtomicReference<>(IngestJobStatus.PENDING);
        private final AtomicReference<Instant> startedAt = new AtomicReference<>();
        private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        JobState(String jobId, String workspaceId, int documentCount, long sequence, Instant createdAt) {
            this.jobId = jobId;
            this.workspaceId = workspaceId;
            this.documentCount = documentCount;
            this.sequence = sequence;
            this.createdAt = createdAt;
        }

        long sequence() {
            return sequence;
        }

        void markStarted() {
            startedAt.compareAndSet(null, Instant.now());
            status.set(IngestJobStatus.RUNNING);
        }

        void markFinished(IngestJobStatus status) {
            this.status.set(status);
            finishedAt.set(Instant.now());
        }

        void markFailed(String message) {
            status.set(IngestJobStatus.FAILED);
            errorMessage.set(message == null ? null : message.strip());
            finishedAt.set(Instant.now());
        }

        JobSnapshot toSnapshot() {
            return new JobSnapshot(
                jobId,
                status.get(),
                documentCount,
                createdAt,
                startedAt.get(),
                finishedAt.get(),
                errorMessage.get()
            );
        }

        String workspaceId() {
            return workspaceId;
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
