package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class StructuredDocumentParser {
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_PATTERN = Pattern.compile("^([-*+]|\\d+[.)])\\s+.+$");
    private final ChunkTextSanitizer textSanitizer;

    StructuredDocumentParser() {
        this(new ChunkTextSanitizer());
    }

    StructuredDocumentParser(ChunkTextSanitizer textSanitizer) {
        this.textSanitizer = Objects.requireNonNull(textSanitizer, "textSanitizer");
    }

    StructuredDocument parse(Document document) {
        var source = Objects.requireNonNull(document, "document");
        var headings = new ArrayList<String>();
        var blocks = new ArrayList<StructuredBlock>();
        int paragraphIndex = 0;
        int listIndex = 0;
        int tableIndex = 0;

        var lines = textSanitizer.sanitizeDocumentLines(source.content());
        int index = 0;
        while (index < lines.size()) {
            var line = lines.get(index).stripTrailing();
            if (line.isBlank()) {
                index++;
                continue;
            }

            var headingMatcher = HEADING_PATTERN.matcher(line);
            if (headingMatcher.matches()) {
                int level = headingMatcher.group(1).length();
                while (headings.size() >= level) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(headingMatcher.group(2).trim());
                index++;
                continue;
            }

            if (isTableLine(line)) {
                var tableLines = new ArrayList<String>();
                while (index < lines.size() && isTableLine(lines.get(index).stripTrailing())) {
                    tableLines.add(lines.get(index).stripTrailing());
                    index++;
                }
                blocks.add(new StructuredBlock(
                    "table:" + tableIndex++,
                    StructuredBlock.Type.TABLE,
                    currentSectionPath(source.title(), headings),
                    String.join("\n", tableLines),
                    Map.of()
                ));
                continue;
            }

            if (isListLine(line)) {
                var listLines = new ArrayList<String>();
                while (index < lines.size() && isListLine(lines.get(index).stripTrailing())) {
                    listLines.add(lines.get(index).stripTrailing());
                    index++;
                }
                if (!blocks.isEmpty()) {
                    var previous = blocks.get(blocks.size() - 1);
                    if (previous.type() == StructuredBlock.Type.PARAGRAPH && isLeadSentence(previous.content())) {
                        blocks.remove(blocks.size() - 1);
                        listLines.add(0, previous.content());
                    }
                }
                blocks.add(new StructuredBlock(
                    "list:" + listIndex++,
                    StructuredBlock.Type.LIST,
                    currentSectionPath(source.title(), headings),
                    String.join("\n", listLines),
                    Map.of()
                ));
                continue;
            }

            var paragraphLines = new ArrayList<String>();
            while (index < lines.size()) {
                var candidate = lines.get(index).stripTrailing();
                if (candidate.isBlank() || HEADING_PATTERN.matcher(candidate).matches() || isTableLine(candidate) || isListLine(candidate)) {
                    break;
                }
                paragraphLines.add(candidate);
                index++;
            }
            blocks.add(new StructuredBlock(
                "paragraph:" + paragraphIndex++,
                StructuredBlock.Type.PARAGRAPH,
                currentSectionPath(source.title(), headings),
                String.join("\n", paragraphLines),
                Map.of()
            ));
        }

        return new StructuredDocument(source.id(), source.title(), List.copyOf(blocks));
    }

    private static String currentSectionPath(String title, List<String> headings) {
        return headings.isEmpty() ? title : String.join(" > ", headings);
    }

    private static boolean isLeadSentence(String content) {
        return content.endsWith(":") || content.endsWith("：");
    }

    private static boolean isListLine(String line) {
        return LIST_PATTERN.matcher(line).matches();
    }

    private static boolean isTableLine(String line) {
        return line.startsWith("|") && line.endsWith("|");
    }
}
