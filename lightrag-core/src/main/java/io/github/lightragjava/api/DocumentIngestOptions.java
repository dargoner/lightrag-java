package io.github.lightragjava.api;

import io.github.lightragjava.indexing.DocumentTypeHint;

import java.util.Map;
import java.util.Objects;

public record DocumentIngestOptions(
    DocumentTypeHint documentTypeHint,
    ChunkGranularity chunkGranularity
) {
    public static final String METADATA_DOCUMENT_TYPE_HINT = "lightrag.documentTypeHint";
    public static final String METADATA_CHUNK_GRANULARITY = "lightrag.chunkGranularity";

    public DocumentIngestOptions {
        documentTypeHint = Objects.requireNonNull(documentTypeHint, "documentTypeHint");
        chunkGranularity = Objects.requireNonNull(chunkGranularity, "chunkGranularity");
    }

    public static DocumentIngestOptions defaults() {
        return new DocumentIngestOptions(DocumentTypeHint.AUTO, ChunkGranularity.MEDIUM);
    }

    public Map<String, String> toMetadata() {
        return Map.of(
            METADATA_DOCUMENT_TYPE_HINT, documentTypeHint.name(),
            METADATA_CHUNK_GRANULARITY, chunkGranularity.name()
        );
    }
}
