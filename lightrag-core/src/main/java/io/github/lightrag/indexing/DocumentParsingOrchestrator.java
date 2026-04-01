package io.github.lightrag.indexing;

import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.types.RawDocumentSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DocumentParsingOrchestrator {
    private final PlainTextParsingProvider plainTextProvider;
    private final MineruParsingProvider mineruProvider;
    private final TikaFallbackParsingProvider tikaProvider;

    public DocumentParsingOrchestrator(PlainTextParsingProvider plainTextProvider) {
        this(plainTextProvider, null, null);
    }

    public DocumentParsingOrchestrator(
        PlainTextParsingProvider plainTextProvider,
        MineruParsingProvider mineruProvider,
        TikaFallbackParsingProvider tikaProvider
    ) {
        this.plainTextProvider = Objects.requireNonNull(plainTextProvider, "plainTextProvider");
        this.mineruProvider = mineruProvider;
        this.tikaProvider = tikaProvider;
    }

    public ParsedDocument parse(RawDocumentSource source) {
        return parse(source, DocumentIngestOptions.defaults());
    }

    public ParsedDocument parse(RawDocumentSource source, DocumentIngestOptions options) {
        var resolved = Objects.requireNonNull(source, "source");
        var resolvedOptions = Objects.requireNonNull(options, "options");
        var parsed = parseWithRouting(resolved);
        var mergedMetadata = mergeMetadata(parsed.metadata(), resolvedOptions);
        return new ParsedDocument(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.blocks(),
            mergedMetadata
        );
    }

    private ParsedDocument parseWithRouting(RawDocumentSource source) {
        var imageSource = isImageSource(source);
        var complexSource = isComplexDocumentSource(source);
        if (isPlainTextSource(source)) {
            return plainTextProvider.parse(source);
        }
        if (imageSource || complexSource) {
            if (mineruProvider == null) {
                if (imageSource) {
                    throw new MineruUnavailableException("MinerU provider is not configured");
                }
                if (tikaProvider == null) {
                    throw new MineruUnavailableException("No parser is configured for complex document: " + source.fileName());
                }
                return downgradeToTika(source, "MinerU provider is not configured");
            }
            try {
                return mineruProvider.parse(source);
            } catch (RuntimeException exception) {
                if (imageSource || tikaProvider == null) {
                    throw exception;
                }
                return downgradeToTika(source, exception.getMessage());
            }
        }
        throw unsupportedMediaType(source);
    }

    private ParsedDocument downgradeToTika(RawDocumentSource source, String reason) {
        var downgraded = tikaProvider.parse(source);
        var metadata = new LinkedHashMap<String, String>(downgraded.metadata());
        metadata.put("parse_error_reason", reason == null ? "MinerU parsing unavailable" : reason);
        return new ParsedDocument(
            downgraded.documentId(),
            downgraded.title(),
            downgraded.plainText(),
            downgraded.blocks(),
            Map.copyOf(metadata)
        );
    }

    private static IllegalArgumentException unsupportedMediaType(RawDocumentSource source) {
        return new IllegalArgumentException("Unsupported media type for document parsing: "
            + source.mediaType() + " (file: " + source.fileName() + ")");
    }

    private static boolean isPlainTextSource(RawDocumentSource source) {
        var mediaType = normalizeMediaType(source.mediaType());
        return isSupportedPlainTextMediaType(mediaType) || hasPlainTextExtension(source.fileName());
    }

    private static String normalizeMediaType(String mediaType) {
        var trimmed = Objects.requireNonNull(mediaType, "mediaType").trim();
        var separator = trimmed.indexOf(';');
        var normalized = separator >= 0 ? trimmed.substring(0, separator) : trimmed;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean isSupportedPlainTextMediaType(String mediaType) {
        return "text/plain".equals(mediaType)
            || "text/markdown".equals(mediaType)
            || "text/x-markdown".equals(mediaType);
    }

    private static boolean hasPlainTextExtension(String fileName) {
        var normalized = Objects.requireNonNull(fileName, "fileName").toLowerCase(Locale.ROOT);
        return normalized.endsWith(".txt")
            || normalized.endsWith(".md")
            || normalized.endsWith(".markdown");
    }

    private static boolean isComplexDocumentSource(RawDocumentSource source) {
        var mediaType = normalizeMediaType(source.mediaType());
        var fileName = source.fileName().toLowerCase(Locale.ROOT);
        return "application/pdf".equals(mediaType)
            || "application/msword".equals(mediaType)
            || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mediaType)
            || "application/vnd.ms-powerpoint".equals(mediaType)
            || "application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mediaType)
            || "text/html".equals(mediaType)
            || "application/xhtml+xml".equals(mediaType)
            || fileName.endsWith(".pdf")
            || fileName.endsWith(".doc")
            || fileName.endsWith(".docx")
            || fileName.endsWith(".ppt")
            || fileName.endsWith(".pptx")
            || fileName.endsWith(".html")
            || fileName.endsWith(".htm");
    }

    private static boolean isImageSource(RawDocumentSource source) {
        var mediaType = normalizeMediaType(source.mediaType());
        var fileName = source.fileName().toLowerCase(Locale.ROOT);
        return mediaType.startsWith("image/")
            || fileName.endsWith(".png")
            || fileName.endsWith(".jpg")
            || fileName.endsWith(".jpeg")
            || fileName.endsWith(".webp")
            || fileName.endsWith(".gif")
            || fileName.endsWith(".bmp");
    }

    private static Map<String, String> mergeMetadata(Map<String, String> metadata, DocumentIngestOptions options) {
        var merged = new LinkedHashMap<String, String>(Objects.requireNonNull(metadata, "metadata"));
        merged.putAll(options.toMetadata());
        return Map.copyOf(merged);
    }
}
