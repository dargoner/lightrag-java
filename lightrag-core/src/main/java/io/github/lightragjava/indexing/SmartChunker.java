package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SmartChunker implements Chunker {
    private final SmartChunkerConfig config;
    private final SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer;
    private final StructuredDocumentParser structuredDocumentParser;
    private final SemanticChunkRefiner semanticChunkRefiner;

    public SmartChunker(SmartChunkerConfig config) {
        this(config, new SentenceBoundaryAnalyzer(), new StructuredDocumentParser(), new SemanticChunkRefiner(SemanticChunkRefiner.defaultSimilarity()));
    }

    SmartChunker(
        SmartChunkerConfig config,
        SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer,
        StructuredDocumentParser structuredDocumentParser,
        SemanticChunkRefiner semanticChunkRefiner
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.sentenceBoundaryAnalyzer = Objects.requireNonNull(sentenceBoundaryAnalyzer, "sentenceBoundaryAnalyzer");
        this.structuredDocumentParser = Objects.requireNonNull(structuredDocumentParser, "structuredDocumentParser");
        this.semanticChunkRefiner = Objects.requireNonNull(semanticChunkRefiner, "semanticChunkRefiner");
    }

    @Override
    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isEmpty()) {
            return List.of();
        }

        var structuralChunks = chunkStructural(source);
        return config.semanticMergeEnabled()
            ? semanticChunkRefiner.refine(source.id(), structuralChunks, config.maxTokens(), config.semanticMergeThreshold())
            : structuralChunks;
    }

    List<Chunk> chunkStructural(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isEmpty()) {
            return List.of();
        }

        var structured = structuredDocumentParser.parse(source);
        var drafts = new ArrayList<ChunkDraft>();
        for (var block : structured.blocks()) {
            drafts.addAll(chunkBlock(block, source.content()));
        }
        return buildChunks(source, drafts);
    }

    private List<Chunk> buildChunks(Document source, List<ChunkDraft> drafts) {
        var chunks = new ArrayList<Chunk>(drafts.size());
        for (int order = 0; order < drafts.size(); order++) {
            var draft = drafts.get(order);
            var chunkId = source.id() + ":" + order;
            var metadata = metadataFor(
                source,
                draft,
                order == 0 ? "" : source.id() + ":" + (order - 1),
                order + 1 >= drafts.size() ? "" : source.id() + ":" + (order + 1)
            );
            var text = draft.text();
            chunks.add(new Chunk(
                chunkId,
                source.id(),
                text,
                text.codePointCount(0, text.length()),
                order,
                metadata
            ));
        }
        return List.copyOf(chunks);
    }

    private List<ChunkDraft> chunkBlock(StructuredBlock block, String sourceContent) {
        return switch (block.type()) {
            case PARAGRAPH -> buildParagraphDrafts(block.content(), block.sectionPath(), block.id());
            case LIST -> buildListDrafts(block);
            case TABLE -> buildTableDrafts(block);
        };
    }

    private List<ChunkDraft> buildParagraphDrafts(String content) {
        return buildParagraphDrafts(content, "", "paragraph:0");
    }

    private List<ChunkDraft> buildParagraphDrafts(String content, String sectionPath, String blockId) {
        if (content.codePointCount(0, content.length()) <= config.maxTokens()) {
            return List.of(new ChunkDraft(content.strip(), sectionPath, "text", blockId, Map.of()));
        }

        var sentences = sentenceBoundaryAnalyzer.split(content);
        if (sentences.stream().anyMatch(sentence -> sentence.codePointCount(0, sentence.length()) > config.maxTokens())) {
            return fallbackChunks(content, sectionPath, blockId, "text", Map.of());
        }
        if (sentences.size() <= 1) {
            return fallbackChunks(content, sectionPath, blockId, "text", Map.of());
        }

        var chunks = new ArrayList<ChunkDraft>();
        int start = 0;
        while (start < sentences.size()) {
            var selected = new ArrayList<String>();
            int tokenCount = 0;
            int endExclusive = start;
            while (endExclusive < sentences.size()) {
                var candidate = sentences.get(endExclusive);
                int candidateTokens = joinTokenCount(selected, candidate);
                if (!selected.isEmpty() && tokenCount + candidateTokens > config.maxTokens()) {
                    break;
                }
                selected.add(candidate);
                tokenCount += candidateTokens;
                endExclusive++;
                if (tokenCount >= config.targetTokens()) {
                    break;
                }
            }
            if (selected.isEmpty()) {
                return fallbackChunks(content, sectionPath, blockId, "text", Map.of());
            }
            chunks.add(new ChunkDraft(String.join(" ", selected), sectionPath, "text", blockId, Map.of()));
            if (endExclusive >= sentences.size()) {
                break;
            }
            int nextStart = rewindStart(start, endExclusive, sentences);
            if (nextStart <= start) {
                nextStart = Math.min(endExclusive, sentences.size());
            }
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private List<ChunkDraft> buildListDrafts(StructuredBlock block) {
        if (block.content().codePointCount(0, block.content().length()) <= config.maxTokens()) {
            return List.of(new ChunkDraft(block.content(), block.sectionPath(), "list", block.id(), Map.of()));
        }
        var lines = List.of(block.content().split("\\R"));
        if (lines.stream().anyMatch(line -> isListItem(line) && line.codePointCount(0, line.length()) > config.maxTokens())) {
            return fallbackChunks(block.content(), block.sectionPath(), block.id(), "list", Map.of());
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        var lead = isListItem(lines.get(0)) ? "" : lines.get(0);
        int itemStart = lead.isEmpty() ? 0 : 1;
        var drafts = new ArrayList<ChunkDraft>();
        var current = new ArrayList<String>();
        if (!lead.isEmpty()) {
            current.add(lead);
        }
        for (int index = itemStart; index < lines.size(); index++) {
            var item = lines.get(index);
            var candidate = current.isEmpty() ? item : String.join("\n", current) + "\n" + item;
            if (!current.isEmpty() && candidate.codePointCount(0, candidate.length()) > config.maxTokens()) {
                drafts.add(new ChunkDraft(String.join("\n", current), block.sectionPath(), "list", block.id(), Map.of()));
                current = new ArrayList<String>();
                if (!lead.isEmpty()) {
                    current.add(lead);
                }
            }
            current.add(item);
        }
        if (!current.isEmpty()) {
            drafts.add(new ChunkDraft(String.join("\n", current), block.sectionPath(), "list", block.id(), Map.of()));
        }
        return List.copyOf(drafts);
    }

    private List<ChunkDraft> buildTableDrafts(StructuredBlock block) {
        var lines = List.of(block.content().split("\\R"));
        if (lines.size() <= 2 || block.content().codePointCount(0, block.content().length()) <= config.maxTokens()) {
            return List.of(new ChunkDraft(block.content(), block.sectionPath(), "table", block.id(), Map.of(
                SmartChunkMetadata.TABLE_PART_INDEX, "1",
                SmartChunkMetadata.TABLE_PART_TOTAL, "1"
            )));
        }
        var header = lines.get(0) + "\n" + lines.get(1);
        var rows = lines.subList(2, lines.size());
        for (var row : rows) {
            var rowWithHeader = header + "\n" + row;
            if (rowWithHeader.codePointCount(0, rowWithHeader.length()) > config.maxTokens()) {
                return fallbackChunks(block.content(), block.sectionPath(), block.id(), "table", Map.of());
            }
        }
        var rows = lines.subList(2, lines.size());
        var chunkTexts = new ArrayList<String>();
        var currentRows = new ArrayList<String>();
        for (var row : rows) {
            var candidateRows = new ArrayList<String>(currentRows);
            candidateRows.add(row);
            var candidateText = header + "\n" + String.join("\n", candidateRows);
            if (!currentRows.isEmpty() && candidateText.codePointCount(0, candidateText.length()) > config.maxTokens()) {
                chunkTexts.add(header + "\n" + String.join("\n", currentRows));
                currentRows = new ArrayList<>();
            }
            currentRows.add(row);
        }
        if (!currentRows.isEmpty()) {
            chunkTexts.add(header + "\n" + String.join("\n", currentRows));
        }
        var drafts = new ArrayList<ChunkDraft>(chunkTexts.size());
        for (int index = 0; index < chunkTexts.size(); index++) {
            drafts.add(new ChunkDraft(
                chunkTexts.get(index),
                block.sectionPath(),
                "table",
                block.id(),
                Map.of(
                    SmartChunkMetadata.TABLE_PART_INDEX, Integer.toString(index + 1),
                    SmartChunkMetadata.TABLE_PART_TOTAL, Integer.toString(chunkTexts.size())
                )
            ));
        }
        return List.copyOf(drafts);
    }

    private int rewindStart(int start, int endExclusive, List<String> sentences) {
        int overlapStart = endExclusive;
        int overlapTokens = 0;
        while (overlapStart > start) {
            var sentence = sentences.get(overlapStart - 1);
            int candidateTokens = sentence.codePointCount(0, sentence.length());
            if (overlapStart < endExclusive) {
                candidateTokens++;
            }
            if (overlapTokens >= config.overlapTokens()) {
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

    private List<ChunkDraft> fallbackChunks(
        String content,
        String sectionPath,
        String blockId,
        String contentType,
        Map<String, String> extraMetadata
    ) {
        int overlap = Math.min(config.overlapTokens(), Math.max(0, config.maxTokens() - 1));
        return new FixedWindowChunker(config.maxTokens(), overlap).chunk(new Document("fallback", "", content, Map.of())).stream()
            .map(Chunk::text)
            .map(text -> new ChunkDraft(text, sectionPath, contentType, blockId, extraMetadata))
            .toList();
    }

    private static int joinTokenCount(List<String> existing, String candidate) {
        int tokenCount = candidate.codePointCount(0, candidate.length());
        return existing.isEmpty() ? tokenCount : tokenCount + 1;
    }

    private static Map<String, String> metadataFor(Document source, ChunkDraft draft, String prevChunkId, String nextChunkId) {
        var metadata = new LinkedHashMap<String, String>(source.metadata());
        metadata.put(SmartChunkMetadata.SECTION_PATH, draft.sectionPath().isBlank() ? source.title() : draft.sectionPath());
        metadata.put(SmartChunkMetadata.CONTENT_TYPE, draft.contentType());
        metadata.put(SmartChunkMetadata.SOURCE_BLOCK_IDS, draft.sourceBlockId());
        metadata.put(SmartChunkMetadata.PREV_CHUNK_ID, prevChunkId);
        metadata.put(SmartChunkMetadata.NEXT_CHUNK_ID, nextChunkId);
        metadata.putAll(draft.extraMetadata());
        return Map.copyOf(metadata);
    }

    private static boolean isListItem(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || line.matches("^\\d+[.)]\\s+.+$");
    }

    private record ChunkDraft(
        String text,
        String sectionPath,
        String contentType,
        String sourceBlockId,
        Map<String, String> extraMetadata
    ) {
    }
}
