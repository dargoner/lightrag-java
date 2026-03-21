package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.types.Document;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
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

    String submit(List<Document> documents) {
        return submit(documents, true);
    }

    String submit(List<Document> documents, boolean async) {
        var jobId = UUID.randomUUID().toString();
        var jobState = new JobState(jobId, documents.size(), sequence.incrementAndGet(), Instant.now());
        jobs.put(jobId, jobState);
        if (async) {
            executor.submit(() -> runJob(jobState, documents));
        } else {
            runJob(jobState, documents);
        }
        return jobId;
    }

    Optional<JobSnapshot> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId)).map(JobState::toSnapshot);
    }

    JobPage listJobs(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }

        var snapshots = jobs.values().stream()
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
            lightRag.ingest(documents);
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
        private final int documentCount;
        private final long sequence;
        private final Instant createdAt;
        private final AtomicReference<IngestJobStatus> status = new AtomicReference<>(IngestJobStatus.PENDING);
        private final AtomicReference<Instant> startedAt = new AtomicReference<>();
        private final AtomicReference<Instant> finishedAt = new AtomicReference<>();
        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        JobState(String jobId, int documentCount, long sequence, Instant createdAt) {
            this.jobId = jobId;
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
    }
}
