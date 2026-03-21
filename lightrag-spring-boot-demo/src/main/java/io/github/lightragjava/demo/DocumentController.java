package io.github.lightragjava.demo;

import io.github.lightragjava.types.Document;
import io.github.lightragjava.spring.boot.LightRagProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
class DocumentController {
    private final IngestJobService ingestJobService;
    private final LightRagProperties properties;

    DocumentController(IngestJobService ingestJobService, LightRagProperties properties) {
        this.ingestJobService = ingestJobService;
        this.properties = properties;
    }

    @PostMapping("/documents/ingest")
    ResponseEntity<IngestJobResponse> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.documents() == null || request.documents().isEmpty()) {
            throw new IllegalArgumentException("documents must not be empty");
        }
        var documents = request.documents().stream()
            .map(payload -> new Document(
                requireNonBlank(payload.id(), "document.id"),
                requireNonBlank(payload.title(), "document.title"),
                requireNonBlank(payload.content(), "document.content"),
                payload.metadata() == null ? Map.of() : payload.metadata()
            ))
            .toList();
        var jobId = ingestJobService.submit(documents, properties.getDemo().isAsyncIngestEnabled());
        return ResponseEntity.accepted().body(new IngestJobResponse(jobId));
    }

    record IngestRequest(List<DocumentPayload> documents) {
    }

    record DocumentPayload(String id, String title, String content, Map<String, String> metadata) {
    }

    record IngestJobResponse(String jobId) {
        public IngestJobResponse {
            jobId = requireNonBlank(jobId, "jobId");
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
