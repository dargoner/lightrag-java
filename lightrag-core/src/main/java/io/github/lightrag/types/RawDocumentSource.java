package io.github.lightrag.types;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record RawDocumentSource(
    String sourceId,
    String fileName,
    String mediaType,
    byte[] bytes,
    Map<String, String> metadata
) {
    public RawDocumentSource {
        sourceId = requireNonBlank(sourceId, "sourceId");
        fileName = requireNonBlank(fileName, "fileName");
        mediaType = requireNonBlank(mediaType, "mediaType");
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    public static RawDocumentSource bytes(String fileName, byte[] bytes) {
        return bytes(fileName, bytes, "text/plain", Map.of());
    }

    public static RawDocumentSource bytes(String fileName, byte[] bytes, String mediaType, Map<String, String> metadata) {
        return new RawDocumentSource(UUID.randomUUID().toString(), fileName, mediaType, bytes, metadata);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
