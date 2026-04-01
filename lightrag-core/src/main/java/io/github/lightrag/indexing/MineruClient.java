package io.github.lightrag.indexing;

import io.github.lightrag.types.RawDocumentSource;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public interface MineruClient {
    String backend();

    ParseResult parse(RawDocumentSource source);

    record ParseResult(
        List<Block> blocks,
        String markdown
    ) {
        public ParseResult {
            blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
            markdown = Objects.requireNonNull(markdown, "markdown");
        }
    }

    record Block(
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
        public Block {
            blockId = Objects.requireNonNull(blockId, "blockId");
            blockType = Objects.requireNonNull(blockType, "blockType");
            text = Objects.requireNonNull(text, "text");
            sectionPath = sectionPath == null ? "" : sectionPath;
            sectionHierarchy = sectionHierarchy == null ? List.of() : List.copyOf(sectionHierarchy);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
