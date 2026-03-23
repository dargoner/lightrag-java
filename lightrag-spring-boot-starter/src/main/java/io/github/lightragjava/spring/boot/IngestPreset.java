package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.ChunkGranularity;
import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.indexing.ChunkingStrategyOverride;
import io.github.lightragjava.indexing.DocumentTypeHint;
import io.github.lightragjava.indexing.ParentChildProfile;
import io.github.lightragjava.indexing.RegexChunkerConfig;

public enum IngestPreset {
    GENERAL(DocumentTypeHint.AUTO, ChunkGranularity.MEDIUM, false),
    LAW(DocumentTypeHint.LAW, ChunkGranularity.MEDIUM, true),
    BOOK(DocumentTypeHint.BOOK, ChunkGranularity.COARSE, true),
    QA(DocumentTypeHint.QA, ChunkGranularity.MEDIUM, true),
    FIGURE(DocumentTypeHint.GENERIC, ChunkGranularity.MEDIUM, true);

    private final DocumentTypeHint documentTypeHint;
    private final ChunkGranularity chunkGranularity;
    private final boolean parentChildEnabled;

    IngestPreset(
        DocumentTypeHint documentTypeHint,
        ChunkGranularity chunkGranularity,
        boolean parentChildEnabled
    ) {
        this.documentTypeHint = documentTypeHint;
        this.chunkGranularity = chunkGranularity;
        this.parentChildEnabled = parentChildEnabled;
    }

    public DocumentIngestOptions toDocumentIngestOptions(int parentChildWindowSize, int parentChildOverlap) {
        var parentChildProfile = parentChildEnabled
            ? ParentChildProfile.enabled(parentChildWindowSize, parentChildOverlap)
            : ParentChildProfile.disabled();
        return new DocumentIngestOptions(
            documentTypeHint,
            chunkGranularity,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            parentChildProfile
        );
    }

    public DocumentTypeHint documentTypeHint() {
        return documentTypeHint;
    }

    public ChunkGranularity chunkGranularity() {
        return chunkGranularity;
    }

    public boolean parentChildEnabled() {
        return parentChildEnabled;
    }
}
