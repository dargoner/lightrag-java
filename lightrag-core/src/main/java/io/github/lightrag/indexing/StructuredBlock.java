package io.github.lightrag.indexing;

import java.util.Map;
import java.util.Objects;

record StructuredBlock(
    String id,
    Type type,
    String sectionPath,
    String content,
    Map<String, String> metadata
) {
    StructuredBlock {
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }

    enum Type {
        PARAGRAPH,
        CAPTION,
        IMAGE,
        LIST,
        TABLE
    }
}
