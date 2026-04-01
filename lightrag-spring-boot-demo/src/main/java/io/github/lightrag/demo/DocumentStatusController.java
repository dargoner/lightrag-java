package io.github.lightrag.demo;

import io.github.lightrag.api.DocumentProcessingStatus;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.spring.boot.LightRagProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/documents")
class DocumentStatusController {
    private final LightRag lightRag;
    private final WorkspaceResolver workspaceResolver;
    private final IngestJobService ingestJobService;
    private final LightRagProperties properties;

    DocumentStatusController(
        LightRag lightRag,
        WorkspaceResolver workspaceResolver,
        IngestJobService ingestJobService,
        LightRagProperties properties
    ) {
        this.lightRag = lightRag;
        this.workspaceResolver = workspaceResolver;
        this.ingestJobService = ingestJobService;
        this.properties = properties;
    }

    @GetMapping("/jobs")
    IngestJobPageResponse listJobs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        HttpServletRequest request
    ) {
        var workspaceId = workspaceResolver.resolve(request);
        var jobPage = ingestJobService.listJobs(workspaceId, page, size);
        return new IngestJobPageResponse(
            jobPage.items().stream().map(DocumentStatusController::toResponse).toList(),
            jobPage.page(),
            jobPage.size(),
            jobPage.total()
        );
    }

    @GetMapping("/jobs/{jobId}")
    IngestJobStatusResponse getJobStatus(@PathVariable String jobId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return ingestJobService.getJob(workspaceId, jobId)
            .map(DocumentStatusController::toResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    ResponseEntity<IngestJobStatusResponse> cancelJob(@PathVariable String jobId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return ResponseEntity.accepted().body(toResponse(ingestJobService.cancel(workspaceId, jobId)));
    }

    @PostMapping("/jobs/{jobId}/retry")
    ResponseEntity<IngestJobStatusResponse> retryJob(@PathVariable String jobId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return ResponseEntity.accepted()
            .body(toResponse(ingestJobService.retry(workspaceId, jobId, properties.getDemo().isAsyncIngestEnabled())));
    }

    @GetMapping("/status")
    List<DocumentProcessingStatus> listDocumentStatus(HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return lightRag.listDocumentStatuses(workspaceId);
    }

    @GetMapping("/status/{documentId}")
    DocumentProcessingStatus getDocumentStatus(@PathVariable String documentId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        try {
            return lightRag.getDocumentStatus(workspaceId, documentId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> deleteDocument(@PathVariable String documentId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        try {
            lightRag.deleteByDocumentId(workspaceId, documentId);
        } catch (NoSuchElementException ignored) {
            // Delete remains idempotent for unknown documents.
        }
        return ResponseEntity.noContent().build();
    }

    private static IngestJobStatusResponse toResponse(IngestJobService.JobSnapshot snapshot) {
        return new IngestJobStatusResponse(
            snapshot.jobId(),
            snapshot.status(),
            snapshot.documentCount(),
            snapshot.createdAt(),
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.errorMessage(),
            snapshot.cancellable(),
            snapshot.retriable(),
            snapshot.retriedFromJobId(),
            snapshot.attempt()
        );
    }

    record IngestJobPageResponse(List<IngestJobStatusResponse> items, int page, int size, int total) {
    }

    record IngestJobStatusResponse(
        String jobId,
        IngestJobService.IngestJobStatus status,
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
}
