package io.github.lightrag.types;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record PreChunkedDocument(
    String documentId,
    String title,
    List<Chunk> chunks,
    Map<String, String> metadata
) {
    public PreChunkedDocument {
        documentId = requireNonBlank(documentId, "documentId");
        title = title == null ? "" : title.strip();
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks must not be empty");
        }
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
