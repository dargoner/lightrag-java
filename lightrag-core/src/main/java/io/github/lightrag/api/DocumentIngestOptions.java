package io.github.lightrag.api;

import io.github.lightrag.indexing.DocumentTypeHint;
import io.github.lightrag.indexing.ChunkingStrategyOverride;
import io.github.lightrag.indexing.ParentChildProfile;
import io.github.lightrag.indexing.RegexChunkerConfig;

import java.util.Map;
import java.util.Objects;

public record DocumentIngestOptions(
    DocumentTypeHint documentTypeHint,
    ChunkGranularity chunkGranularity,
    ChunkingStrategyOverride strategyOverride,
    RegexChunkerConfig regexConfig,
    ParentChildProfile parentChildProfile,
    ProcessOptions processOptions,
    ChunkOptions chunkOptions
) {
    public static final String METADATA_DOCUMENT_TYPE_HINT = "lightrag.documentTypeHint";
    public static final String METADATA_CHUNK_GRANULARITY = "lightrag.chunkGranularity";
    public static final String METADATA_PROCESS_OPTIONS = "process_options";
    public static final String METADATA_CHUNK_OPTIONS = "chunk_options";

    public DocumentIngestOptions {
        documentTypeHint = Objects.requireNonNull(documentTypeHint, "documentTypeHint");
        chunkGranularity = Objects.requireNonNull(chunkGranularity, "chunkGranularity");
        strategyOverride = Objects.requireNonNull(strategyOverride, "strategyOverride");
        regexConfig = Objects.requireNonNull(regexConfig, "regexConfig");
        parentChildProfile = Objects.requireNonNull(parentChildProfile, "parentChildProfile");
        processOptions = (processOptions == null ? ProcessOptions.defaults() : processOptions)
            .withChunkingStrategy(strategyOverride);
        if (processOptions.chunkingStrategy() != ChunkingStrategyOverride.AUTO) {
            strategyOverride = processOptions.chunkingStrategy();
        }
        chunkOptions = chunkOptions == null
            ? ChunkOptions.forProcessOptions(processOptions)
            : ChunkOptions.slim(chunkOptions.values(), processOptions);
    }

    public DocumentIngestOptions(DocumentTypeHint documentTypeHint, ChunkGranularity chunkGranularity) {
        this(
            documentTypeHint,
            chunkGranularity,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled(),
            ProcessOptions.defaults(),
            null
        );
    }

    public DocumentIngestOptions(
        DocumentTypeHint documentTypeHint,
        ChunkGranularity chunkGranularity,
        String chunkingStrategy
    ) {
        this(
            documentTypeHint,
            chunkGranularity,
            ChunkingStrategyOverride.fromExternalName(chunkingStrategy),
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled(),
            ProcessOptions.defaults(),
            null
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

    public DocumentIngestOptions(
        DocumentTypeHint documentTypeHint,
        ChunkGranularity chunkGranularity,
        ChunkingStrategyOverride strategyOverride,
        RegexChunkerConfig regexConfig,
        ParentChildProfile parentChildProfile
    ) {
        this(
            documentTypeHint,
            chunkGranularity,
            strategyOverride,
            regexConfig,
            parentChildProfile,
            ProcessOptions.defaults(),
            null
        );
    }

    public DocumentIngestOptions(
        DocumentTypeHint documentTypeHint,
        ChunkGranularity chunkGranularity,
        String processOptions,
        Map<String, ?> chunkOptions
    ) {
        this(
            documentTypeHint,
            chunkGranularity,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled(),
            new ProcessOptions(processOptions),
            ChunkOptions.from(chunkOptions)
        );
    }

    public static DocumentIngestOptions defaults() {
        return new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.AUTO,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled(),
            ProcessOptions.defaults(),
            null
        );
    }

    public Map<String, String> toMetadata() {
        var metadata = new java.util.LinkedHashMap<String, String>();
        metadata.put(METADATA_DOCUMENT_TYPE_HINT, documentTypeHint.name());
        metadata.put(METADATA_CHUNK_GRANULARITY, chunkGranularity.name());
        metadata.put(METADATA_PROCESS_OPTIONS, processOptions.value());
        metadata.put(METADATA_CHUNK_OPTIONS, chunkOptions.toJson());
        return Map.copyOf(metadata);
    }
}
