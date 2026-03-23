package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ParentChildChunkBuilder {
    public static final String METADATA_CHUNK_LEVEL = "lightrag.chunkLevel";
    public static final String METADATA_PARENT_CHUNK_ID = "lightrag.parentChunkId";
    public static final String METADATA_PARENT_SUMMARY = "lightrag.parentSummary";
    public static final String METADATA_SEARCHABLE = "lightrag.searchable";
    public static final String CHUNK_LEVEL_PARENT = "parent";
    public static final String CHUNK_LEVEL_CHILD = "child";
    private final SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer;

    public ParentChildChunkBuilder() {
        this(new SentenceBoundaryAnalyzer());
    }

    ParentChildChunkBuilder(SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer) {
        this.sentenceBoundaryAnalyzer = Objects.requireNonNull(sentenceBoundaryAnalyzer, "sentenceBoundaryAnalyzer");
    }

    public ParentChildChunkSet build(ChunkingResult result, ParentChildProfile profile) {
        var source = Objects.requireNonNull(result, "result");
        var resolvedProfile = Objects.requireNonNull(profile, "profile");
        if (!resolvedProfile.enabled()) {
            var parentChunks = source.chunks().stream()
                .map(this::toParentChunk)
                .toList();
            return new ParentChildChunkSet(parentChunks, List.of());
        }
        var parentChunks = source.chunks().stream()
            .map(this::toParentChunkForHierarchicalSearch)
            .toList();
        var childChunks = new ArrayList<Chunk>();
        for (var parentChunk : parentChunks) {
            childChunks.addAll(toChildChunks(parentChunk, resolvedProfile));
        }
        return new ParentChildChunkSet(parentChunks, normalizeChildOrders(childChunks));
    }

    private Chunk toParentChunk(Chunk chunk) {
        return toParentChunk(chunk, null);
    }

    private Chunk toParentChunkForHierarchicalSearch(Chunk chunk) {
        var kind = chunkKind(chunk);
        boolean searchable = switch (kind) {
            case CAPTION -> true;
            case IMAGE -> false;
            case TABLE, TEXT -> false;
        };
        return toParentChunk(chunk, searchable);
    }

    private Chunk toParentChunk(Chunk chunk, Boolean searchable) {
        var metadata = new LinkedHashMap<String, String>(chunk.metadata());
        metadata.put(METADATA_CHUNK_LEVEL, CHUNK_LEVEL_PARENT);
        var summary = parentSummary(chunk);
        if (!summary.isBlank()) {
            metadata.put(METADATA_PARENT_SUMMARY, summary);
        }
        if (searchable != null) {
            metadata.put(METADATA_SEARCHABLE, Boolean.toString(searchable));
        }
        return new Chunk(
            chunk.id(),
            chunk.documentId(),
            chunk.text(),
            chunk.tokenCount(),
            chunk.order(),
            Map.copyOf(metadata)
        );
    }

    private List<Chunk> toChildChunks(Chunk parentChunk, ParentChildProfile profile) {
        if (parentChunk.text().isEmpty()) {
            return List.of();
        }
        var childTexts = switch (chunkKind(parentChunk)) {
            case CAPTION, IMAGE -> List.<String>of();
            case TABLE -> splitTableChildTexts(parentChunk.text(), profile);
            case TEXT -> splitSemanticChildTexts(parentChunk.text(), profile);
        };
        var childChunks = new ArrayList<Chunk>();
        int childOrder = 0;
        var parentSummary = parentChunk.metadata().getOrDefault(METADATA_PARENT_SUMMARY, "");
        for (var text : childTexts) {
            var metadata = new LinkedHashMap<String, String>(parentChunk.metadata());
            metadata.put(METADATA_CHUNK_LEVEL, CHUNK_LEVEL_CHILD);
            metadata.put(METADATA_PARENT_CHUNK_ID, parentChunk.id());
            metadata.put(METADATA_SEARCHABLE, Boolean.TRUE.toString());
            if (!parentSummary.isBlank()) {
                metadata.put(METADATA_PARENT_SUMMARY, parentSummary);
            }
            childChunks.add(new Chunk(
                parentChunk.id() + "#child:" + childOrder,
                parentChunk.documentId(),
                text,
                text.codePointCount(0, text.length()),
                childOrder,
                Map.copyOf(metadata)
            ));
            childOrder++;
        }
        return List.copyOf(childChunks);
    }

    private List<String> splitTableChildTexts(String content, ParentChildProfile profile) {
        var lines = content.lines()
            .map(String::stripTrailing)
            .filter(line -> !line.isBlank())
            .toList();
        if (lines.size() <= 2 || !lines.get(0).startsWith("|") || !lines.get(1).startsWith("|")) {
            return List.of(content.strip());
        }
        var header = lines.get(0) + "\n" + lines.get(1);
        var rows = lines.subList(2, lines.size());
        var groups = new ArrayList<String>();
        var currentRows = new ArrayList<String>();
        for (var row : rows) {
            var candidateRows = new ArrayList<String>(currentRows);
            candidateRows.add(row);
            var candidateText = header + "\n" + String.join("\n", candidateRows);
            if (!currentRows.isEmpty()
                && candidateText.codePointCount(0, candidateText.length()) > profile.childWindowSize()) {
                groups.add(header + "\n" + String.join("\n", currentRows));
                currentRows = new ArrayList<>();
            }
            var singleRowText = header + "\n" + row;
            if (singleRowText.codePointCount(0, singleRowText.length()) > profile.childWindowSize()) {
                return List.of(content.strip());
            }
            currentRows.add(row);
        }
        if (!currentRows.isEmpty()) {
            groups.add(header + "\n" + String.join("\n", currentRows));
        }
        return groups.isEmpty() ? List.of(content.strip()) : List.copyOf(groups);
    }

    private List<String> splitSemanticChildTexts(String content, ParentChildProfile profile) {
        var sentences = sentenceBoundaryAnalyzer.split(content);
        if (sentences.isEmpty()) {
            return List.of(content);
        }
        if (sentences.size() == 1) {
            return splitFixedWindow(content, profile);
        }
        if (sentences.stream().anyMatch(sentence -> sentence.codePointCount(0, sentence.length()) > profile.childWindowSize())) {
            return splitFixedWindow(content, profile);
        }

        var childTexts = new ArrayList<String>();
        int start = 0;
        while (start < sentences.size()) {
            var selected = new ArrayList<String>();
            int tokenCount = 0;
            int endExclusive = start;
            while (endExclusive < sentences.size()) {
                var candidate = sentences.get(endExclusive);
                int candidateTokens = joinTokenCount(selected, candidate);
                if (!selected.isEmpty() && tokenCount + candidateTokens > profile.childWindowSize()) {
                    break;
                }
                selected.add(candidate);
                tokenCount += candidateTokens;
                endExclusive++;
            }
            if (selected.isEmpty()) {
                return splitFixedWindow(content, profile);
            }
            childTexts.add(String.join(" ", selected));
            if (endExclusive >= sentences.size()) {
                break;
            }
            int nextStart = rewindStart(start, endExclusive, sentences, profile.childOverlap());
            if (nextStart <= start) {
                nextStart = Math.min(endExclusive, sentences.size());
            }
            start = nextStart;
        }
        return List.copyOf(childTexts);
    }

    private static List<Chunk> normalizeChildOrders(List<Chunk> childChunks) {
        var normalized = new ArrayList<Chunk>(childChunks.size());
        for (int index = 0; index < childChunks.size(); index++) {
            var childChunk = childChunks.get(index);
            normalized.add(new Chunk(
                childChunk.id(),
                childChunk.documentId(),
                childChunk.text(),
                childChunk.tokenCount(),
                index,
                childChunk.metadata()
            ));
        }
        return List.copyOf(normalized);
    }

    private static List<String> splitFixedWindow(String content, ParentChildProfile profile) {
        int overlap = Math.min(profile.childOverlap(), Math.max(0, profile.childWindowSize() - 1));
        return new FixedWindowChunker(profile.childWindowSize(), overlap)
            .chunk(new io.github.lightragjava.types.Document("parent-child-fallback", "", content, Map.of()))
            .stream()
            .map(Chunk::text)
            .toList();
    }

    private static int joinTokenCount(List<String> existing, String candidate) {
        int tokenCount = candidate.codePointCount(0, candidate.length());
        return existing.isEmpty() ? tokenCount : tokenCount + 1;
    }

    private static int rewindStart(int start, int endExclusive, List<String> sentences, int overlapTokenBudget) {
        int overlapStart = endExclusive;
        int overlapTokens = 0;
        while (overlapStart > start) {
            var sentence = sentences.get(overlapStart - 1);
            int candidateTokens = sentence.codePointCount(0, sentence.length());
            if (overlapStart < endExclusive) {
                candidateTokens++;
            }
            if (overlapTokens >= overlapTokenBudget) {
                break;
            }
            overlapTokens += candidateTokens;
            overlapStart--;
        }
        if (overlapStart == start) {
            overlapStart = Math.min(endExclusive - 1, sentences.size() - 1);
        }
        return overlapStart;
    }

    private String parentSummary(Chunk chunk) {
        var firstSentence = sentenceBoundaryAnalyzer.split(chunk.text()).stream()
            .findFirst()
            .orElseGet(chunk::text)
            .strip();
        var sectionPath = chunk.metadata().getOrDefault(SmartChunkMetadata.SECTION_PATH, "").strip();
        var truncatedSentence = truncate(firstSentence, 48);
        if (sectionPath.isBlank()) {
            return truncatedSentence;
        }
        if (truncatedSentence.isBlank()) {
            return sectionPath;
        }
        return sectionPath.equals(truncatedSentence) ? sectionPath : sectionPath + " | " + truncatedSentence;
    }

    private static ChunkKind chunkKind(Chunk chunk) {
        var contentType = chunk.metadata().getOrDefault(SmartChunkMetadata.CONTENT_TYPE, "").strip().toLowerCase(java.util.Locale.ROOT);
        if ("caption".equals(contentType) || looksLikeCaption(chunk.text())) {
            return ChunkKind.CAPTION;
        }
        if ("image_placeholder".equals(contentType) || chunk.text().strip().startsWith("images/")) {
            return ChunkKind.IMAGE;
        }
        if ("table".equals(contentType) || looksLikeMarkdownTable(chunk.text())) {
            return ChunkKind.TABLE;
        }
        return ChunkKind.TEXT;
    }

    private static boolean looksLikeCaption(String text) {
        var normalized = text == null ? "" : text.strip();
        return normalized.matches("^(?:图|表|附图|附表|figure|fig\\.?|table)\\s*[0-9一二三四五六七八九十]+(?:[.．-]\\s*[0-9]+)*(?:\\s*[:：]?\\s*.*)?$");
    }

    private static boolean looksLikeMarkdownTable(String text) {
        var lines = text == null ? List.<String>of() : text.lines().map(String::strip).filter(line -> !line.isBlank()).toList();
        return lines.size() >= 2 && lines.get(0).startsWith("|") && lines.get(1).startsWith("|");
    }

    private static String truncate(String text, int maxCodePoints) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var normalized = text.strip();
        if (normalized.codePointCount(0, normalized.length()) <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end).strip();
    }

    private enum ChunkKind {
        TEXT,
        CAPTION,
        IMAGE,
        TABLE
    }
}
