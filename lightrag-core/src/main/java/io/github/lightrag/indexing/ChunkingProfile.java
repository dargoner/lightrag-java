package io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkGranularity;

import java.util.Objects;

public record ChunkingProfile(
    DocumentType documentType,
    ChunkGranularity chunkGranularity,
    ChunkingStrategyOverride strategyOverride,
    RegexChunkerConfig regexConfig
) {
    public ChunkingProfile {
        documentType = Objects.requireNonNull(documentType, "documentType");
        chunkGranularity = Objects.requireNonNull(chunkGranularity, "chunkGranularity");
        strategyOverride = Objects.requireNonNull(strategyOverride, "strategyOverride");
        regexConfig = Objects.requireNonNull(regexConfig, "regexConfig");
    }

    public boolean hasRegexRules() {
        return regexConfig.hasRules();
    }

    public SmartChunkerConfig smartChunkerConfig() {
        return switch (chunkGranularity) {
            case FINE -> SmartChunkerConfig.builder()
                .targetTokens(300)
                .maxTokens(500)
                .overlapTokens(60)
                .semanticMergeEnabled(true)
                .build();
            case MEDIUM -> SmartChunkerConfig.builder()
                .targetTokens(800)
                .maxTokens(1_200)
                .overlapTokens(100)
                .semanticMergeEnabled(true)
                .build();
            case COARSE -> SmartChunkerConfig.builder()
                .targetTokens(1_200)
                .maxTokens(1_800)
                .overlapTokens(160)
                .semanticMergeEnabled(true)
                .build();
        };
    }

    public FixedWindowChunker fixedWindowChunker() {
        return switch (chunkGranularity) {
            case FINE -> new FixedWindowChunker(500, 60);
            case MEDIUM -> new FixedWindowChunker(1_000, 100);
            case COARSE -> new FixedWindowChunker(1_600, 160);
        };
    }

    public RecursiveCharacterChunker recursiveCharacterChunker() {
        return switch (chunkGranularity) {
            case FINE -> new RecursiveCharacterChunker(500, 60);
            case MEDIUM -> new RecursiveCharacterChunker(1_000, 100);
            case COARSE -> new RecursiveCharacterChunker(1_600, 160);
        };
    }

    public int semanticVectorChunkSize() {
        return switch (chunkGranularity) {
            case FINE -> 500;
            case MEDIUM -> 1_000;
            case COARSE -> 1_600;
        };
    }
}
