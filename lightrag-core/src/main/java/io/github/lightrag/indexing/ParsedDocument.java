package io.github.lightrag.indexing;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ParsedDocument(
    String documentId,
    String title,
    String plainText,
    List<ParsedBlock> blocks,
    Map<String, String> metadata
) {
    public ParsedDocument {
        documentId = Objects.requireNonNull(documentId, "documentId");
        title = Objects.requireNonNull(title, "title");
        plainText = Objects.requireNonNull(plainText, "plainText");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
