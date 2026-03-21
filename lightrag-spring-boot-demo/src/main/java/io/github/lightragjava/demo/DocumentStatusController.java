package io.github.lightragjava.demo;

import io.github.lightragjava.api.DocumentProcessingStatus;
import io.github.lightragjava.api.LightRag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/documents")
class DocumentStatusController {
    private final LightRag lightRag;
    private final IngestJobService ingestJobService;

    DocumentStatusController(LightRag lightRag, IngestJobService ingestJobService) {
        this.lightRag = lightRag;
        this.ingestJobService = ingestJobService;
    }

    @GetMapping("/jobs/{jobId}")
    IngestJobStatusResponse getJobStatus(@PathVariable String jobId) {
        return ingestJobService.getJob(jobId)
            .map(snapshot -> new IngestJobStatusResponse(snapshot.jobId(), snapshot.status(), snapshot.errorMessage()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
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

    record IngestJobStatusResponse(String jobId, IngestJobService.IngestJobStatus status, String errorMessage) {
    }
}
