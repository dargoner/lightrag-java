package io.github.lightrag.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class ParagraphSemanticChunker {
    private static final double IDEAL_RATIO = 0.75d;
    private static final double TABLE_MAX_RATIO = 0.625d;
    private static final double TABLE_IDEAL_RATIO = 0.375d;
    private static final double TABLE_MIN_LAST_RATIO = 0.32d;
    private static final double SMALL_TAIL_RATIO = 0.125d;
    private static final int MAX_ANCHOR_CANDIDATE_LENGTH = 100;
    private static final Pattern HTML_ROW_PATTERN = Pattern.compile("(?is)<tr\\b.*?</tr>");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final String METADATA_HEADING = "paragraph_semantic.heading";
    static final String METADATA_PARENT_HEADINGS = "paragraph_semantic.parent_headings";
    static final String METADATA_LEVEL = "paragraph_semantic.level";
    static final String METADATA_TABLE_ROLE = "paragraph_semantic.table_role";

    private final SmartChunkerConfig config;
    private final RecursiveCharacterChunker fallbackChunker;

    public ParagraphSemanticChunker(SmartChunkerConfig config, RecursiveCharacterChunker fallbackChunker) {
        this.config = Objects.requireNonNull(config, "config");
        this.fallbackChunker = Objects.requireNonNull(fallbackChunker, "fallbackChunker");
    }

    public ChunkingResult chunk(ParsedDocument document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.blocks().isEmpty()) {
            return fallbackToRecursive(source, "paragraph semantic sidecar blocks are unavailable");
        }

        var targetMax = Math.max(config.maxTokens(), 1);
        var targetIdeal = Math.max((int) (targetMax * IDEAL_RATIO), 1);
        var tableMax = Math.max((int) (targetMax * TABLE_MAX_RATIO), 1);
        var tableIdeal = Math.max((int) (targetMax * TABLE_IDEAL_RATIO), 1);
        var tableMinLast = Math.max((int) (tableMax * TABLE_MIN_LAST_RATIO), 1);
        var smallTailThreshold = Math.max((int) (targetMax * SMALL_TAIL_RATIO), 1);

        var splitBlocks = new ArrayList<SemanticBlock>();
        for (var block : source.blocks().stream()
            .sorted(Comparator.comparing(ParsedBlock::readingOrder, Comparator.nullsLast(Integer::compareTo)))
            .toList()) {
            if (block.text().isBlank()) {
                continue;
            }
            var semanticBlock = toSemanticBlock(block, source.title());
            var expanded = expandTables(semanticBlock, targetMax, tableMax, tableIdeal, tableMinLast);
            var afterLongSplit = new ArrayList<SemanticBlock>();
            for (var candidate : expanded) {
                afterLongSplit.addAll(splitLongBlock(candidate, targetMax, targetIdeal));
            }
            splitBlocks.addAll(applyPartSuffixes(afterLongSplit));
        }

        if (splitBlocks.isEmpty()) {
            return fallbackToRecursive(source, "paragraph semantic sidecar contains no content rows");
        }

        var merged = mergeSmallBlocks(splitBlocks, targetMax, targetIdeal, smallTailThreshold);
        return new ChunkingResult(toChunks(source, merged), ChunkingMode.PARAGRAPH, false, null);
    }

    private ChunkingResult fallbackToRecursive(ParsedDocument document, String reason) {
        var fallback = fallbackChunker.chunk(document);
        return new ChunkingResult(
            fallback.chunks(),
            ChunkingMode.RECURSIVE,
            false,
            reason + "; upstream P fallback to R applied"
        );
    }

    private SemanticBlock toSemanticBlock(ParsedBlock block, String title) {
        var hierarchy = block.sectionHierarchy();
        var heading = hierarchy.isEmpty() ? block.sectionPath() : hierarchy.get(hierarchy.size() - 1);
        if (heading.isBlank()) {
            heading = title == null || title.isBlank() ? "Untitled" : title;
        }
        var parentHeadings = hierarchy.size() <= 1 ? List.<String>of() : hierarchy.subList(0, hierarchy.size() - 1);
        return new SemanticBlock(
            heading,
            List.copyOf(parentHeadings),
            resolveLevel(block),
            blockToParagraphs(block.text()),
            "none",
            List.of(block.blockId())
        );
    }

    private static int resolveLevel(ParsedBlock block) {
        var explicit = block.metadata().get("sidecar.level");
        if (explicit == null || explicit.isBlank()) {
            return Math.max(block.sectionHierarchy().size(), 1);
        }
        try {
            return Math.max(Integer.parseInt(explicit), 1);
        } catch (NumberFormatException ignored) {
            return Math.max(block.sectionHierarchy().size(), 1);
        }
    }

    private List<Paragraph> blockToParagraphs(String content) {
        var paragraphs = new ArrayList<Paragraph>();
        for (var line : content.split("\\R")) {
            if (!line.isBlank()) {
                paragraphs.add(new Paragraph(line.strip(), isTableParagraph(line)));
            }
        }
        if (paragraphs.isEmpty() && !content.isBlank()) {
            paragraphs.add(new Paragraph(content.strip(), isTableParagraph(content)));
        }
        return List.copyOf(paragraphs);
    }

    private static boolean isTableParagraph(String text) {
        var stripped = text.strip();
        return stripped.startsWith("<table ") && stripped.endsWith("</table>");
    }

    private List<SemanticBlock> expandTables(
        SemanticBlock block,
        int targetMax,
        int tableMax,
        int tableIdeal,
        int tableMinLast
    ) {
        if (block.paragraphs().stream().noneMatch(paragraph -> paragraph.isTable() && tokens(paragraph.text()) > tableMax)) {
            return List.of(block);
        }

        var out = new ArrayList<SemanticBlock>();
        var current = new ArrayList<Paragraph>();
        String currentRole = "none";
        for (var paragraph : block.paragraphs()) {
            if (!paragraph.isTable() || tokens(paragraph.text()) <= tableMax) {
                current.add(paragraph);
                continue;
            }
            var slices = splitTable(paragraph.text(), tableMax, tableIdeal, tableMinLast);
            if (slices.size() <= 1) {
                current.add(paragraph);
                continue;
            }
            if ("last".equals(currentRole)) {
                out.addAll(bridgeConsecutiveLargeTables(block, current, slices.get(0), targetMax));
                current.clear();
            } else if (!current.isEmpty()) {
                current.add(new Paragraph(slices.get(0), true));
                out.add(block.withParagraphs(List.copyOf(current), "first"));
                current.clear();
            } else {
                out.add(block.withParagraphs(List.of(new Paragraph(slices.get(0), true)), "first"));
            }
            for (int index = 1; index < slices.size() - 1; index++) {
                out.add(block.withParagraphs(List.of(new Paragraph(slices.get(index), true)), "middle"));
            }
            current.add(new Paragraph(slices.get(slices.size() - 1), true));
            currentRole = "last";
        }
        if (!current.isEmpty()) {
            out.add(block.withParagraphs(List.copyOf(current), currentRole));
        }
        return List.copyOf(out);
    }

    private List<SemanticBlock> bridgeConsecutiveLargeTables(
        SemanticBlock block,
        List<Paragraph> previousLastWithBridge,
        String nextFirstSlice,
        int targetMax
    ) {
        var previousLast = previousLastWithBridge.get(0);
        var bridge = joinParagraphs(previousLastWithBridge.subList(1, previousLastWithBridge.size()));
        var bridgeTokens = tokens(bridge);
        var previousBudget = bridgeBudget(targetMax, previousLast.text());
        var nextBudget = bridgeBudget(targetMax, nextFirstSlice);
        String leftBridge;
        String rightBridge;
        String middleBridge;
        if (bridgeTokens <= previousBudget && bridgeTokens <= nextBudget) {
            leftBridge = bridge;
            rightBridge = bridge;
            middleBridge = "";
        } else {
            leftBridge = prefixTokens(bridge, previousBudget);
            rightBridge = suffixTokens(bridge, nextBudget);
            middleBridge = middleTokens(bridge, previousBudget, nextBudget);
        }

        var result = new ArrayList<SemanticBlock>();
        result.add(block.withParagraphs(appendText(previousLast, leftBridge), "last"));
        if (!middleBridge.isBlank()) {
            result.add(block.withParagraphs(List.of(new Paragraph(middleBridge, false)), "none"));
        }
        var nextFirst = new ArrayList<Paragraph>();
        if (!rightBridge.isBlank()) {
            nextFirst.add(new Paragraph(rightBridge, false));
        }
        nextFirst.add(new Paragraph(nextFirstSlice, true));
        result.add(block.withParagraphs(List.copyOf(nextFirst), "first"));
        return List.copyOf(result);
    }

    private int bridgeBudget(int targetMax, String boundaryText) {
        var overlapCap = Math.min(boundedOverlap(targetMax), Math.max(targetMax / 2, 0));
        return Math.min(overlapCap, Math.max(targetMax - tokens(boundaryText), 0));
    }

    private static List<Paragraph> appendText(Paragraph base, String text) {
        if (text.isBlank()) {
            return List.of(base);
        }
        return List.of(base, new Paragraph(text, false));
    }

    private List<String> splitTable(String text, int tableMax, int tableIdeal, int tableMinLast) {
        var htmlRows = htmlRows(text);
        if (htmlRows.size() > 1) {
            return splitHtmlTableByRows(text, htmlRows, tableMax, tableIdeal, tableMinLast);
        }
        var jsonRows = jsonRows(text);
        if (jsonRows.size() > 1) {
            return splitJsonTableByRows(text, jsonRows, tableMax, tableIdeal, tableMinLast);
        }
        return fixedSplitText(text, tableMax, 0);
    }

    private static List<String> htmlRows(String text) {
        var matcher = HTML_ROW_PATTERN.matcher(text);
        var rows = new ArrayList<String>();
        while (matcher.find()) {
            rows.add(matcher.group());
        }
        return List.copyOf(rows);
    }

    private static List<String> jsonRows(String text) {
        var body = tableBody(text);
        if (body.isBlank() || !body.stripLeading().startsWith("[")) {
            return List.of();
        }
        try {
            var node = OBJECT_MAPPER.readTree(body);
            if (!node.isArray()) {
                return List.of();
            }
            var rows = new ArrayList<String>();
            for (JsonNode item : node) {
                rows.add(item.toString());
            }
            return List.copyOf(rows);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static String tableBody(String text) {
        var start = text.indexOf('>');
        var end = text.lastIndexOf("</table>");
        if (start < 0 || end <= start) {
            return "";
        }
        return text.substring(start + 1, end).strip();
    }

    private List<String> splitHtmlTableByRows(String original, List<String> rows, int tableMax, int tableIdeal, int tableMinLast) {
        var start = original.indexOf(rows.get(0));
        var end = original.lastIndexOf(rows.get(rows.size() - 1)) + rows.get(rows.size() - 1).length();
        var prefix = start <= 0 ? "<table>" : original.substring(0, start);
        var suffix = end >= original.length() ? "</table>" : original.substring(end);
        var bodyLimits = tableBodyLimits(prefix, suffix, tableMax, tableIdeal, tableMinLast);
        var chunks = new ArrayList<String>();
        for (var rowChunk : splitRowsByTokens(rows, joined -> String.join("", joined), bodyLimits)) {
            chunks.addAll(serializeRowsWithOverflowSplit(
                rowChunk,
                joined -> String.join("", joined),
                joined -> prefix + String.join("", joined) + suffix,
                bodyLimits
            ));
        }
        return List.copyOf(chunks);
    }

    private List<String> splitJsonTableByRows(String original, List<String> rows, int tableMax, int tableIdeal, int tableMinLast) {
        var start = original.indexOf('>');
        var end = original.lastIndexOf("</table>");
        var prefix = start < 0 ? "<table>" : original.substring(0, start + 1);
        var suffix = end < 0 ? "</table>" : original.substring(end);
        var bodyLimits = tableBodyLimits(prefix, suffix, tableMax, tableIdeal, tableMinLast);
        var chunks = new ArrayList<String>();
        for (var rowChunk : splitRowsByTokens(rows, joined -> "[" + String.join(",", joined) + "]", bodyLimits)) {
            chunks.addAll(serializeRowsWithOverflowSplit(
                rowChunk,
                joined -> "[" + String.join(",", joined) + "]",
                joined -> wrapJsonRows(prefix, joined, suffix),
                bodyLimits
            ));
        }
        return List.copyOf(chunks);
    }

    private List<List<String>> splitRowsByTokens(
        List<String> rows,
        Function<List<String>, String> bodySerializer,
        TableBodyLimits limits
    ) {
        var total = tokens(bodySerializer.apply(rows));
        if (total <= limits.bodyMax() || rows.size() <= 1) {
            return List.of(rows);
        }

        var targetChunks = Math.max(
            (int) Math.ceil((double) total / limits.bodyIdeal()),
            (int) Math.ceil((double) total / limits.bodyMax())
        );
        targetChunks = Math.min(targetChunks, rows.size());
        var targetRows = (double) rows.size() / targetChunks;
        var chunks = new ArrayList<List<String>>();
        var start = 0;
        for (int index = 0; index < targetChunks; index++) {
            int end;
            if (index == targetChunks - 1) {
                end = rows.size();
            } else {
                end = Math.max(start + 1, Math.min((int) ((index + 1) * targetRows), rows.size()));
                var remaining = rows.size() - end;
                if (remaining > 0 && remaining < targetRows * 0.3d) {
                    end = rows.size();
                }
            }
            chunks.add(List.copyOf(rows.subList(start, end)));
            start = end;
            if (start >= rows.size()) {
                break;
            }
        }

        if (chunks.size() >= 2 && tokens(bodySerializer.apply(chunks.get(chunks.size() - 1))) < limits.bodyLastMin()) {
            var previous = chunks.get(chunks.size() - 2);
            var last = chunks.get(chunks.size() - 1);
            var merged = new ArrayList<String>(previous.size() + last.size());
            merged.addAll(previous);
            merged.addAll(last);
            if (tokens(bodySerializer.apply(merged)) <= limits.bodyMax()) {
                chunks.set(chunks.size() - 2, List.copyOf(merged));
                chunks.remove(chunks.size() - 1);
            }
        }
        return List.copyOf(chunks);
    }

    private List<String> serializeRowsWithOverflowSplit(
        List<String> rows,
        Function<List<String>, String> bodySerializer,
        Function<List<String>, String> serializer,
        TableBodyLimits limits
    ) {
        var wrapped = serializer.apply(rows);
        if (tokens(wrapped) <= limits.tableMax()) {
            return List.of(wrapped);
        }
        if (rows.size() <= 1) {
            return fixedSplitText(wrapped, limits.tableMax(), 0);
        }
        var halved = Math.max(tokens(wrapped) / 2, 1);
        var nextLimits = new TableBodyLimits(
            limits.tableMax(),
            Math.max(Math.min(limits.bodyMax(), halved), 1),
            Math.max(Math.min(limits.bodyIdeal(), halved / 2), 1),
            Math.max(Math.min(limits.bodyLastMin(), halved / 2), 1)
        );
        var pieces = new ArrayList<String>();
        for (var splitRows : splitRowsByTokens(rows, bodySerializer, nextLimits)) {
            if (splitRows.equals(rows)) {
                pieces.addAll(fixedSplitText(wrapped, limits.tableMax(), 0));
            } else {
                pieces.addAll(serializeRowsWithOverflowSplit(splitRows, bodySerializer, serializer, nextLimits));
            }
        }
        return List.copyOf(pieces);
    }

    private static TableBodyLimits tableBodyLimits(String prefix, String suffix, int tableMax, int tableIdeal, int tableMinLast) {
        var wrapperOverhead = tokens(prefix + suffix);
        return new TableBodyLimits(
            tableMax,
            Math.max(tableMax - wrapperOverhead, 1),
            Math.max(Math.min(tableIdeal, tableMax) - wrapperOverhead, 1),
            Math.max(tableMinLast - wrapperOverhead, 1)
        );
    }

    private static String wrapJsonRows(String prefix, List<String> rows, String suffix) {
        return prefix + "[" + String.join(",", rows) + "]" + suffix;
    }

    private List<SemanticBlock> splitLongBlock(SemanticBlock block, int targetMax, int targetIdeal) {
        if (tokens(block.content()) <= targetMax) {
            return List.of(block);
        }

        var anchors = new ArrayList<Integer>();
        var paragraphs = block.paragraphs();
        for (int index = 1; index < paragraphs.size(); index++) {
            var paragraph = paragraphs.get(index);
            if (!paragraph.isTable() && paragraph.text().length() <= MAX_ANCHOR_CANDIDATE_LENGTH) {
                anchors.add(index);
            }
        }
        if (anchors.isEmpty()) {
            return fixedSplitText(block.content(), targetMax, boundedOverlap(targetMax))
                .stream()
                .map(text -> block.withParagraphs(List.of(new Paragraph(text, isTableParagraph(text))), block.tableRole()))
                .toList();
        }

        var chunks = new ArrayList<SemanticBlock>();
        var current = new ArrayList<Paragraph>();
        for (var paragraph : paragraphs) {
            if (tokens(paragraph.text()) > targetMax) {
                if (!current.isEmpty()) {
                    chunks.add(block.withParagraphs(List.copyOf(current), block.tableRole()));
                    current.clear();
                }
                fixedSplitText(paragraph.text(), targetMax, paragraph.isTable() ? 0 : boundedOverlap(targetMax))
                    .stream()
                    .map(text -> new Paragraph(text, paragraph.isTable() && isTableParagraph(text)))
                    .map(split -> block.withParagraphs(List.of(split), block.tableRole()))
                    .forEach(chunks::add);
                continue;
            }
            var candidate = new ArrayList<>(current);
            candidate.add(paragraph);
            if (!current.isEmpty() && tokens(joinParagraphs(candidate)) > targetMax) {
                chunks.add(block.withParagraphs(List.copyOf(current), block.tableRole()));
                current.clear();
            }
            current.add(paragraph);
            if (tokens(joinParagraphs(current)) >= targetIdeal) {
                chunks.add(block.withParagraphs(List.copyOf(current), block.tableRole()));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(block.withParagraphs(List.copyOf(current), block.tableRole()));
        }
        return List.copyOf(chunks);
    }

    private List<SemanticBlock> applyPartSuffixes(List<SemanticBlock> blocks) {
        if (blocks.size() <= 1) {
            return blocks;
        }
        var result = new ArrayList<SemanticBlock>(blocks.size());
        for (int index = 0; index < blocks.size(); index++) {
            result.add(blocks.get(index).withHeading(blocks.get(index).heading() + " [part " + (index + 1) + "]"));
        }
        return List.copyOf(result);
    }

    private List<SemanticBlock> mergeSmallBlocks(
        List<SemanticBlock> blocks,
        int targetMax,
        int targetIdeal,
        int smallTailThreshold
    ) {
        var result = new ArrayList<SemanticBlock>();
        for (var block : blocks) {
            if (!result.isEmpty()) {
                var previous = result.get(result.size() - 1);
                var combined = previous.merge(block);
                var combinedTokens = tokens(combined.content());
                var canTailAbsorb = tokens(block.content()) < smallTailThreshold && tokens(previous.content()) >= targetIdeal;
                if ((tokens(previous.content()) < targetIdeal || canTailAbsorb)
                    && combinedTokens <= targetMax
                    && canMerge(previous, block)) {
                    result.set(result.size() - 1, combined);
                    continue;
                }
            }
            result.add(block);
        }
        return List.copyOf(result);
    }

    private boolean canMerge(SemanticBlock left, SemanticBlock right) {
        if ("middle".equals(left.tableRole()) || "middle".equals(right.tableRole())) {
            return false;
        }
        if ("first".equals(right.tableRole()) || "last".equals(left.tableRole())) {
            return false;
        }
        if (left.level() == right.level()) {
            return left.heading().equals(right.heading()) && left.parentHeadings().equals(right.parentHeadings());
        }
        return isAncestor(left, right) || isAncestor(right, left);
    }

    private static boolean isAncestor(SemanticBlock ancestor, SemanticBlock descendant) {
        var descendantPath = new ArrayList<>(descendant.parentHeadings());
        descendantPath.add(descendant.heading());
        var ancestorPath = new ArrayList<>(ancestor.parentHeadings());
        ancestorPath.add(ancestor.heading());
        if (ancestorPath.size() >= descendantPath.size()) {
            return false;
        }
        return descendantPath.subList(0, ancestorPath.size()).equals(ancestorPath);
    }

    private List<Chunk> toChunks(ParsedDocument document, List<SemanticBlock> blocks) {
        var chunks = new ArrayList<Chunk>(blocks.size());
        for (int order = 0; order < blocks.size(); order++) {
            var block = blocks.get(order);
            var chunkId = document.documentId() + ":" + order;
            var metadata = new LinkedHashMap<String, String>(document.metadata());
            metadata.put(SmartChunkMetadata.SECTION_PATH, block.sectionPath(document.title()));
            metadata.put(SmartChunkMetadata.CONTENT_TYPE, block.contentType());
            metadata.put(SmartChunkMetadata.SOURCE_BLOCK_IDS, String.join(",", block.blockIds()));
            metadata.put(SmartChunkMetadata.PREV_CHUNK_ID, order == 0 ? "" : document.documentId() + ":" + (order - 1));
            metadata.put(SmartChunkMetadata.NEXT_CHUNK_ID, order + 1 >= blocks.size() ? "" : document.documentId() + ":" + (order + 1));
            metadata.put(METADATA_HEADING, block.heading());
            metadata.put(METADATA_PARENT_HEADINGS, String.join(" > ", block.parentHeadings()));
            metadata.put(METADATA_LEVEL, Integer.toString(block.level()));
            metadata.put(METADATA_TABLE_ROLE, block.tableRole());
            var text = block.content().strip();
            chunks.add(new Chunk(
                chunkId,
                document.documentId(),
                text,
                tokens(text),
                order,
                Map.copyOf(metadata)
            ));
        }
        return List.copyOf(chunks);
    }

    private List<String> fixedSplitText(String text, int windowSize, int overlap) {
        if (text.isBlank()) {
            return List.of();
        }
        return new FixedWindowChunker(windowSize, Math.min(overlap, Math.max(0, windowSize - 1)))
            .chunk(new Document("paragraph-fallback", "", text, Map.of()))
            .stream()
            .map(Chunk::text)
            .toList();
    }

    private int boundedOverlap(int targetMax) {
        return Math.min(config.overlapTokens(), Math.max(0, targetMax - 1));
    }

    private static int tokens(String text) {
        return text.codePointCount(0, text.length());
    }

    private static String prefixTokens(String text, int tokenCount) {
        if (text.isBlank() || tokenCount <= 0) {
            return "";
        }
        var end = text.offsetByCodePoints(0, Math.min(tokenCount, tokens(text)));
        return text.substring(0, end).strip();
    }

    private static String suffixTokens(String text, int tokenCount) {
        if (text.isBlank() || tokenCount <= 0) {
            return "";
        }
        var total = tokens(text);
        var start = text.offsetByCodePoints(0, Math.max(total - Math.min(tokenCount, total), 0));
        return text.substring(start).strip();
    }

    private static String middleTokens(String text, int prefixTokenCount, int suffixTokenCount) {
        var total = tokens(text);
        var startTokens = Math.min(Math.max(prefixTokenCount, 0), total);
        var endTokens = Math.max(startTokens, total - Math.min(Math.max(suffixTokenCount, 0), total));
        if (startTokens >= endTokens) {
            return "";
        }
        var start = text.offsetByCodePoints(0, startTokens);
        var end = text.offsetByCodePoints(0, endTokens);
        return text.substring(start, end).strip();
    }

    private static String joinParagraphs(List<Paragraph> paragraphs) {
        return paragraphs.stream().map(Paragraph::text).collect(java.util.stream.Collectors.joining("\n"));
    }

    private record Paragraph(String text, boolean isTable) {
    }

    private record TableBodyLimits(int tableMax, int bodyMax, int bodyIdeal, int bodyLastMin) {
    }

    private record SemanticBlock(
        String heading,
        List<String> parentHeadings,
        int level,
        List<Paragraph> paragraphs,
        String tableRole,
        List<String> blockIds
    ) {
        private SemanticBlock {
            parentHeadings = List.copyOf(parentHeadings);
            paragraphs = List.copyOf(paragraphs);
            blockIds = List.copyOf(blockIds);
        }

        String content() {
            return joinParagraphs(paragraphs);
        }

        String sectionPath(String title) {
            var parts = new ArrayList<>(parentHeadings);
            parts.add(heading);
            var path = parts.stream()
                .filter(part -> part != null && !part.isBlank())
                .collect(java.util.stream.Collectors.joining(" > "));
            if (!path.isBlank()) {
                return path;
            }
            return title == null || title.isBlank() ? "Untitled" : title;
        }

        String contentType() {
            if (paragraphs.stream().allMatch(Paragraph::isTable)) {
                return "table";
            }
            if (paragraphs.stream().anyMatch(Paragraph::isTable)) {
                return "mixed";
            }
            return "text";
        }

        SemanticBlock withParagraphs(List<Paragraph> newParagraphs, String newTableRole) {
            return new SemanticBlock(heading, parentHeadings, level, newParagraphs, newTableRole, blockIds);
        }

        SemanticBlock withHeading(String newHeading) {
            return new SemanticBlock(newHeading, parentHeadings, level, paragraphs, tableRole, blockIds);
        }

        SemanticBlock merge(SemanticBlock other) {
            var mergedParagraphs = new ArrayList<>(paragraphs);
            mergedParagraphs.addAll(other.paragraphs);
            var mergedBlockIds = new ArrayList<>(blockIds);
            for (var blockId : other.blockIds) {
                if (!mergedBlockIds.contains(blockId)) {
                    mergedBlockIds.add(blockId);
                }
            }
            return new SemanticBlock(heading, parentHeadings, level, mergedParagraphs, "none", mergedBlockIds);
        }
    }
}
