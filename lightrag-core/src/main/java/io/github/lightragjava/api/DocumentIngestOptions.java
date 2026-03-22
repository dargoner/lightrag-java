package io.github.lightragjava.api;

import io.github.lightragjava.indexing.DocumentTypeHint;

import java.util.Objects;

public record DocumentIngestOptions(
    DocumentTypeHint documentTypeHint,
    ChunkGranularity chunkGranularity
) {
    public DocumentIngestOptions {
        documentTypeHint = Objects.requireNonNull(documentTypeHint, "documentTypeHint");
        chunkGranularity = Objects.requireNonNull(chunkGranularity, "chunkGranularity");
    }

    public static DocumentIngestOptions defaults() {
        return new DocumentIngestOptions(DocumentTypeHint.AUTO, ChunkGranularity.MEDIUM);
    }
}
