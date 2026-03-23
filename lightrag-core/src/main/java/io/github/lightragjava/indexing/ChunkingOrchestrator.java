package io.github.lightragjava.indexing;

import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ChunkingOrchestrator {
    public static final String METADATA_EFFECTIVE_MODE = "lightrag.chunking.effectiveMode";
    public static final String METADATA_DOWNGRADED_TO_FIXED = "lightrag.chunking.downgradedToFixed";
    public static final String METADATA_FALLBACK_REASON = "lightrag.chunking.fallbackReason";

    private final DocumentTypeResolver documentTypeResolver;
    private final SmartChunker smartChunkerOverride;
    private final RegexChunker regexChunkerOverride;
    private final FixedWindowChunker fixedChunkerOverride;

    public ChunkingOrchestrator() {
        this(new DocumentTypeResolver(), null, null, null);
    }

    ChunkingOrchestrator(
        DocumentTypeResolver documentTypeResolver,
        SmartChunker smartChunker,
        RegexChunker regexChunker,
        FixedWindowChunker fixedChunker
    ) {
        this.documentTypeResolver = Objects.requireNonNull(documentTypeResolver, "documentTypeResolver");
        this.smartChunkerOverride = smartChunker;
        this.regexChunkerOverride = regexChunker;
        this.fixedChunkerOverride = fixedChunker;
    }

    public ChunkingResult chunk(ParsedDocument document, DocumentIngestOptions options) {
        var parsed = Objects.requireNonNull(document, "document");
        var resolvedOptions = Objects.requireNonNull(options, "options");
        var profile = new ChunkingProfile(
            documentTypeResolver.resolve(resolvedOptions.documentTypeHint()),
            resolvedOptions.chunkGranularity(),
            resolvedOptions.strategyOverride(),
            resolvedOptions.regexConfig()
        );
        var initialMode = resolveInitialMode(profile);
        var result = switch (initialMode) {
            case REGEX -> regexChunker(profile).chunk(parsed, profile.regexConfig());
            case SMART -> smartChunk(parsed, profile);
            case FIXED -> fixedChunk(parsed, profile);
        };
        return withDiagnostics(result, parsed);
    }

    static ChunkingMode resolveInitialMode(ChunkingProfile profile) {
        return switch (Objects.requireNonNull(profile, "profile").strategyOverride()) {
            case SMART -> ChunkingMode.SMART;
            case REGEX -> ChunkingMode.REGEX;
            case FIXED -> ChunkingMode.FIXED;
            case AUTO -> profile.hasRegexRules() ? ChunkingMode.REGEX : ChunkingMode.SMART;
        };
    }

    private ChunkingResult smartChunk(ParsedDocument document, ChunkingProfile profile) {
        if (!document.blocks().isEmpty()) {
            return new ChunkingResult(
                smartChunker(profile).chunkParsedDocument(document, profile.documentType()),
                ChunkingMode.SMART,
                false,
                null
            );
        }
        var source = new Document(document.documentId(), document.title(), document.plainText(), document.metadata());
        return new ChunkingResult(
            smartChunker(profile).chunk(source),
            ChunkingMode.SMART,
            false,
            null
        );
    }

    private ChunkingResult fixedChunk(ParsedDocument document, ChunkingProfile profile) {
        var source = new Document(document.documentId(), document.title(), document.plainText(), document.metadata());
        return new ChunkingResult(
            fixedChunker(profile).chunk(source),
            ChunkingMode.FIXED,
            false,
            null
        );
    }

    private ChunkingResult withDiagnostics(ChunkingResult result, ParsedDocument parsed) {
        var chunks = result.chunks().stream()
            .map(chunk -> new Chunk(
                chunk.id(),
                chunk.documentId(),
                chunk.text(),
                chunk.tokenCount(),
                chunk.order(),
                metadataWithDiagnostics(chunk.metadata(), result, parsed)
            ))
            .toList();
        return new ChunkingResult(chunks, result.effectiveMode(), result.downgradedToFixed(), result.fallbackReason());
    }

    private static Map<String, String> metadataWithDiagnostics(
        Map<String, String> source,
        ChunkingResult result,
        ParsedDocument parsed
    ) {
        var metadata = new LinkedHashMap<String, String>(source);
        metadata.put(METADATA_EFFECTIVE_MODE, result.effectiveMode().name());
        metadata.put(METADATA_DOWNGRADED_TO_FIXED, Boolean.toString(result.downgradedToFixed()));
        if (result.fallbackReason() != null && !result.fallbackReason().isBlank()) {
            metadata.put(METADATA_FALLBACK_REASON, result.fallbackReason());
        }
        if (isTikaPlaintextFallback(parsed)) {
            metadata.put(SmartChunkMetadata.PARSE_QUALITY, "fallback_plaintext");
            metadata.put(SmartChunkMetadata.IMAGE_PATH_UNAVAILABLE, "true");
        }
        return Map.copyOf(metadata);
    }

    private static boolean isTikaPlaintextFallback(ParsedDocument parsed) {
        return parsed != null
            && parsed.blocks().isEmpty()
            && "tika".equalsIgnoreCase(parsed.metadata().getOrDefault("parse_mode", ""));
    }

    private SmartChunker smartChunker(ChunkingProfile profile) {
        return smartChunkerOverride != null ? smartChunkerOverride : new SmartChunker(profile.smartChunkerConfig());
    }

    private RegexChunker regexChunker(ChunkingProfile profile) {
        return regexChunkerOverride != null ? regexChunkerOverride : new RegexChunker(fixedChunker(profile));
    }

    private FixedWindowChunker fixedChunker(ChunkingProfile profile) {
        return fixedChunkerOverride != null ? fixedChunkerOverride : profile.fixedWindowChunker();
    }
}
