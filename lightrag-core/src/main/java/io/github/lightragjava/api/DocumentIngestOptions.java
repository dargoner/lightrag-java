package io.github.lightragjava.api;

import io.github.lightragjava.indexing.DocumentTypeHint;
import io.github.lightragjava.indexing.ChunkingStrategyOverride;
import io.github.lightragjava.indexing.ParentChildProfile;
import io.github.lightragjava.indexing.RegexChunkerConfig;

import java.util.Map;
import java.util.Objects;

public record DocumentIngestOptions(
    DocumentTypeHint documentTypeHint,
    ChunkGranularity chunkGranularity,
    ChunkingStrategyOverride strategyOverride,
    RegexChunkerConfig regexConfig,
    ParentChildProfile parentChildProfile
) {
    public static final String METADATA_DOCUMENT_TYPE_HINT = "lightrag.documentTypeHint";
    public static final String METADATA_CHUNK_GRANULARITY = "lightrag.chunkGranularity";

    public DocumentIngestOptions {
        documentTypeHint = Objects.requireNonNull(documentTypeHint, "documentTypeHint");
        chunkGranularity = Objects.requireNonNull(chunkGranularity, "chunkGranularity");
        strategyOverride = Objects.requireNonNull(strategyOverride, "strategyOverride");
        regexConfig = Objects.requireNonNull(regexConfig, "regexConfig");
        parentChildProfile = Objects.requireNonNull(parentChildProfile, "parentChildProfile");
    }

    public DocumentIngestOptions(DocumentTypeHint documentTypeHint, ChunkGranularity chunkGranularity) {
        this(
            documentTypeHint,
            chunkGranularity,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled()
        );
    }

    public DocumentIngestOptions(
        DocumentTypeHint documentTypeHint,
        ChunkGranularity chunkGranularity,
        ChunkingStrategyOverride strategyOverride,
        RegexChunkerConfig regexConfig
    ) {
        this(documentTypeHint, chunkGranularity, strategyOverride, regexConfig, ParentChildProfile.disabled());
    }

    public static DocumentIngestOptions defaults() {
        return new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled()
        );
    }

    public Map<String, String> toMetadata() {
        return Map.of(
            METADATA_DOCUMENT_TYPE_HINT, documentTypeHint.name(),
            METADATA_CHUNK_GRANULARITY, chunkGranularity.name()
        );
    }
}
