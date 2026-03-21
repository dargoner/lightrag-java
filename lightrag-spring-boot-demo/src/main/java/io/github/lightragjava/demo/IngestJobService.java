package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.types.Document;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

@Service
class IngestJobService {
    private final LightRag lightRag;
    private final ExecutorService executor;
    private final ConcurrentMap<String, JobState> jobs = new ConcurrentHashMap<>();

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
        var jobState = new JobState(jobId);
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runJob(JobState jobState, List<Document> documents) {
        jobState.setStatus(IngestJobStatus.RUNNING);
        try {
            lightRag.ingest(documents);
            jobState.setStatus(IngestJobStatus.SUCCEEDED);
        } catch (Throwable throwable) {
            jobState.setStatus(IngestJobStatus.FAILED);
            jobState.setErrorMessage(throwable.getMessage());
        }
    }

    record JobSnapshot(String jobId, IngestJobStatus status, String errorMessage) {
    }

    enum IngestJobStatus {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    private static final class JobState {
        private final String jobId;
        private final AtomicReference<IngestJobStatus> status = new AtomicReference<>(IngestJobStatus.PENDING);
        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        JobState(String jobId) {
            this.jobId = jobId;
        }

        void setStatus(IngestJobStatus status) {
            this.status.set(status);
        }

        void setErrorMessage(String message) {
            errorMessage.set(message == null ? null : message.strip());
        }

        JobSnapshot toSnapshot() {
            return new JobSnapshot(jobId, status.get(), errorMessage.get());
        }
    }
}
