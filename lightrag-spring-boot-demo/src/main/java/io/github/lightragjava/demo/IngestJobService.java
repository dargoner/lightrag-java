package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import io.github.lightragjava.types.Document;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
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
    private final WorkspaceLightRagFactory workspaceLightRagFactory;
    private final ExecutorService executor;
    private final ConcurrentMap<String, JobState> jobs = new ConcurrentHashMap<>();

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
        var jobState = new JobState(jobId, requireNonBlank(workspaceId, "workspaceId"));
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

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runJob(JobState jobState, List<Document> documents) {
        jobState.setStatus(IngestJobStatus.RUNNING);
        try {
            workspaceLightRagFactory.get(jobState.workspaceId()).ingest(documents);
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
        private final String workspaceId;
        private final AtomicReference<IngestJobStatus> status = new AtomicReference<>(IngestJobStatus.PENDING);
        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        JobState(String jobId, String workspaceId) {
            this.jobId = jobId;
            this.workspaceId = workspaceId;
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
