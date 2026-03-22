package io.github.lightragjava.indexing;

import io.github.lightragjava.api.DocumentIngestOptions;
import io.github.lightragjava.types.RawDocumentSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DocumentParsingOrchestrator {
    private final PlainTextParsingProvider plainTextProvider;

    public DocumentParsingOrchestrator(PlainTextParsingProvider plainTextProvider) {
        this.plainTextProvider = Objects.requireNonNull(plainTextProvider, "plainTextProvider");
    }

    public ParsedDocument parse(RawDocumentSource source) {
        return parse(source, DocumentIngestOptions.defaults());
    }

    public ParsedDocument parse(RawDocumentSource source, DocumentIngestOptions options) {
        var resolved = Objects.requireNonNull(source, "source");
        var resolvedOptions = Objects.requireNonNull(options, "options");
        ensurePlainTextSupported(resolved);
        var parsed = plainTextProvider.parse(resolved);
        var mergedMetadata = mergeMetadata(parsed.metadata(), resolvedOptions);
        return new ParsedDocument(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.blocks(),
            mergedMetadata
        );
    }

    private static void ensurePlainTextSupported(RawDocumentSource source) {
        var mediaType = normalizeMediaType(source.mediaType());
        if (!mediaType.startsWith("text/")) {
            throw new IllegalArgumentException("Unsupported media type for plain-text ingest: "
                + source.mediaType() + " (file: " + source.fileName() + ")");
        }
        if (!isSupportedMediaType(mediaType) && !hasSupportedExtension(source.fileName())) {
            throw new IllegalArgumentException("Unsupported media type for plain-text ingest: "
                + source.mediaType() + " (file: " + source.fileName() + ")");
        }
    }

    private static String normalizeMediaType(String mediaType) {
        var trimmed = Objects.requireNonNull(mediaType, "mediaType").trim();
        var separator = trimmed.indexOf(';');
        var normalized = separator >= 0 ? trimmed.substring(0, separator) : trimmed;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isSupportedMediaType(String mediaType) {
        return "text/plain".equals(mediaType)
            || "text/markdown".equals(mediaType)
            || "text/x-markdown".equals(mediaType);
    }

    private static boolean hasSupportedExtension(String fileName) {
        var normalized = Objects.requireNonNull(fileName, "fileName").toLowerCase(Locale.ROOT);
        return normalized.endsWith(".txt")
            || normalized.endsWith(".md")
            || normalized.endsWith(".markdown");
    }

    private static Map<String, String> mergeMetadata(Map<String, String> metadata, DocumentIngestOptions options) {
        var merged = new LinkedHashMap<String, String>(Objects.requireNonNull(metadata, "metadata"));
        merged.putAll(options.toMetadata());
        return Map.copyOf(merged);
    }
}
