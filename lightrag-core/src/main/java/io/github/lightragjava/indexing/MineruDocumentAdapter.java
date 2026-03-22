package io.github.lightragjava.indexing;

import io.github.lightragjava.types.RawDocumentSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MineruDocumentAdapter {
    public ParsedDocument adapt(MineruClient.ParseResult result, RawDocumentSource source) {
        var parseResult = Objects.requireNonNull(result, "result");
        var rawSource = Objects.requireNonNull(source, "source");
        var blocks = parseResult.blocks().stream()
            .map(block -> new ParsedBlock(
                block.blockId(),
                block.blockType(),
                block.text(),
                block.sectionPath(),
                block.sectionHierarchy(),
                block.pageNo(),
                block.bbox(),
                block.readingOrder(),
                block.metadata()
            ))
            .toList();
        var plainText = resolvedPlainText(parseResult, blocks);
        return new ParsedDocument(
            rawSource.sourceId(),
            rawSource.fileName(),
            plainText,
            blocks,
            Map.copyOf(new LinkedHashMap<>(rawSource.metadata()))
        );
    }

    private static String resolvedPlainText(MineruClient.ParseResult result, List<ParsedBlock> blocks) {
        var structuredText = blocks.stream()
            .map(ParsedBlock::text)
            .filter(text -> !text.isBlank())
            .reduce((left, right) -> left + "\n\n" + right)
            .orElse("");
        if (!structuredText.isBlank()) {
            return structuredText;
        }
        return result.markdown();
    }
}
