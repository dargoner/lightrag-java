package io.github.lightragjava.demo;

import io.github.lightragjava.api.DocumentProcessingStatus;
import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.spring.boot.WorkspaceLightRagFactory;
import jakarta.servlet.http.HttpServletRequest;
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
    private final WorkspaceLightRagFactory workspaceLightRagFactory;
    private final WorkspaceResolver workspaceResolver;
    private final IngestJobService ingestJobService;

    DocumentStatusController(
        WorkspaceLightRagFactory workspaceLightRagFactory,
        WorkspaceResolver workspaceResolver,
        IngestJobService ingestJobService
    ) {
        this.workspaceLightRagFactory = workspaceLightRagFactory;
        this.workspaceResolver = workspaceResolver;
        this.ingestJobService = ingestJobService;
    }

    @GetMapping("/jobs/{jobId}")
    IngestJobStatusResponse getJobStatus(@PathVariable String jobId, HttpServletRequest request) {
        var workspaceId = workspaceResolver.resolve(request);
        return ingestJobService.getJob(workspaceId, jobId)
            .map(snapshot -> new IngestJobStatusResponse(snapshot.jobId(), snapshot.status(), snapshot.errorMessage()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found: " + jobId));
    }

    @GetMapping("/status")
    List<DocumentProcessingStatus> listDocumentStatus(HttpServletRequest request) {
        return lightRag(request).listDocumentStatuses();
    }

    @GetMapping("/status/{documentId}")
    DocumentProcessingStatus getDocumentStatus(@PathVariable String documentId, HttpServletRequest request) {
        try {
            return lightRag(request).getDocumentStatus(documentId);
        } catch (NoSuchElementException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @DeleteMapping("/{documentId}")
    ResponseEntity<Void> deleteDocument(@PathVariable String documentId, HttpServletRequest request) {
        lightRag(request).deleteByDocumentId(documentId);
        return ResponseEntity.noContent().build();
    }

    private LightRag lightRag(HttpServletRequest request) {
        return workspaceLightRagFactory.get(workspaceResolver.resolve(request));
    }

    record IngestJobStatusResponse(String jobId, IngestJobService.IngestJobStatus status, String errorMessage) {
    }
}
