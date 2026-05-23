package io.github.lightrag.types;

import java.util.Map;
import java.util.Objects;

public record PreChunkedChunk(
    String documentId,
    String title,
    Chunk chunk,
    Map<String, String> documentMetadata
) {
    public PreChunkedChunk {
        documentId = requireNonBlank(documentId, "documentId");
        title = title == null ? "" : title.strip();
        chunk = Objects.requireNonNull(chunk, "chunk");
        documentMetadata = Map.copyOf(Objects.requireNonNull(documentMetadata, "documentMetadata"));
        if (!chunk.documentId().equals(documentId)) {
            throw new IllegalArgumentException("chunk documentId must match source document id: " + documentId);
        }
    }

    public PreChunkedChunk(
        String documentId,
        String title,
        String chunkId,
        String text,
        int tokenCount,
        int order,
        Map<String, String> documentMetadata,
        Map<String, String> chunkMetadata
    ) {
        this(
            documentId,
            title,
            new Chunk(chunkId, documentId, text, tokenCount, order, chunkMetadata),
            documentMetadata
        );
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
