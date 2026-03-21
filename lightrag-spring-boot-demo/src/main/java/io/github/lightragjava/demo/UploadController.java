package io.github.lightragjava.demo;

import io.github.lightragjava.spring.boot.LightRagProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

@RestController
class UploadController {
    private final IngestJobService ingestJobService;
    private final UploadedDocumentMapper uploadedDocumentMapper;
    private final WorkspaceResolver workspaceResolver;
    private final LightRagProperties properties;

    UploadController(
        IngestJobService ingestJobService,
        UploadedDocumentMapper uploadedDocumentMapper,
        WorkspaceResolver workspaceResolver,
        LightRagProperties properties
    ) {
        this.ingestJobService = ingestJobService;
        this.uploadedDocumentMapper = uploadedDocumentMapper;
        this.workspaceResolver = workspaceResolver;
        this.properties = properties;
    }

    @PostMapping(path = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadJobResponse> upload(
        @RequestPart("files") List<MultipartFile> files,
        @RequestParam(name = "async", required = false) Boolean async,
        HttpServletRequest request
    ) {
        var documents = uploadedDocumentMapper.toDocuments(files);
        var runAsync = async == null ? properties.getDemo().isAsyncIngestEnabled() : async;
        var workspaceId = workspaceResolver.resolve(request);
        var jobId = ingestJobService.submit(workspaceId, documents, runAsync);
        return ResponseEntity.accepted().body(new UploadJobResponse(
            jobId,
            documents.stream().map(document -> document.id()).toList()
        ));
    }

    record UploadJobResponse(String jobId, List<String> documentIds) {
        UploadJobResponse {
            jobId = requireNonBlank(jobId, "jobId");
            if (documentIds == null || documentIds.isEmpty()) {
                throw new IllegalArgumentException("documentIds must not be empty");
            }
            documentIds = List.copyOf(documentIds);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
