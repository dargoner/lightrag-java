package io.github.lightrag.indexing;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ParsedBlock(
    String blockId,
    String blockType,
    String text,
    String sectionPath,
    List<String> sectionHierarchy,
    Integer pageNo,
    String bbox,
    Integer readingOrder,
    Map<String, String> metadata
) {
    public ParsedBlock {
        blockId = Objects.requireNonNull(blockId, "blockId");
        blockType = Objects.requireNonNull(blockType, "blockType");
        text = Objects.requireNonNull(text, "text");
        sectionPath = Objects.requireNonNull(sectionPath, "sectionPath");
        sectionHierarchy = List.copyOf(Objects.requireNonNull(sectionHierarchy, "sectionHierarchy"));
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
    }
}
