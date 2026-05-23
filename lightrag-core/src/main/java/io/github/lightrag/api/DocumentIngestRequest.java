package io.github.lightrag.api;

import io.github.lightrag.types.Document;

import java.util.List;
import java.util.Objects;

public record DocumentIngestRequest(List<Document> documents) {
    public DocumentIngestRequest {
        documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
    }

    public static DocumentIngestRequest of(List<Document> documents) {
        return new DocumentIngestRequest(documents);
    }
}
