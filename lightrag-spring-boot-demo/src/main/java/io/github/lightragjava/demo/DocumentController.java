package io.github.lightragjava.demo;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.types.Document;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
class DocumentController {
    private final LightRag lightRag;

    DocumentController(LightRag lightRag) {
        this.lightRag = lightRag;
    }

    @PostMapping("/documents/ingest")
    void ingest(@RequestBody IngestRequest request) {
        lightRag.ingest(request.documents().stream()
            .map(document -> new Document(
                document.id(),
                document.title(),
                document.content(),
                document.metadata()
            ))
            .toList());
    }

    record IngestRequest(List<DocumentPayload> documents) {
    }

    record DocumentPayload(String id, String title, String content, Map<String, String> metadata) {
    }
}
