package io.github.lightragjava.demo;

import io.github.lightragjava.api.DocumentProcessingStatus;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.spring.boot.LightRagProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/documents")
class DocumentStatusController {
    private final LightRag lightRag;
    private final IngestJobService ingestJobService;
    private final LightRagProperties properties;

    DocumentStatusController(LightRag lightRag, IngestJobService ingestJobService, LightRagProperties properties) {
        this.lightRag = lightRag;
        this.ingestJobService = ingestJobService;
        this.properties = properties;
    }

    @GetMapping("/jobs")
    IngestJobPageResponse listJobs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        var jobPage = ingestJobService.listJobs(page, size);
        return new IngestJobPageResponse(
            jobPage.items().stream().map(DocumentStatusController::toResponse).toList(),
            jobPage.page(),
            jobPage.size(),
            jobPage.total()
        );
    }

    @GetMapping("/jobs/{jobId}")
    IngestJobStatusResponse getJobStatus(@PathVariable String jobId) {
        return ingestJobService.getJob(jobId)
            .map(DocumentStatusController::toResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    ResponseEntity<IngestJobStatusResponse> cancelJob(@PathVariable String jobId) {
        return ResponseEntity.accepted().body(toResponse(ingestJobService.cancel(jobId)));
    }

    @PostMapping("/jobs/{jobId}/retry")
    ResponseEntity<IngestJobStatusResponse> retryJob(@PathVariable String jobId) {
        return ResponseEntity.accepted()
            .body(toResponse(ingestJobService.retry(jobId, properties.getDemo().isAsyncIngestEnabled())));
    }

    @GetMapping("/status")
    List<DocumentProcessingStatus> listDocumentStatus() {
        return lightRag.listDocumentStatuses();
    }

    @GetMapping("/status/{documentId}")
    DocumentProcessingStatus getDocumentStatus(@PathVariable String documentId) {
        try {
            return lightRag.getDocumentStatus(documentId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> deleteDocument(@PathVariable String documentId) {
        lightRag.deleteByDocumentId(documentId);
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
