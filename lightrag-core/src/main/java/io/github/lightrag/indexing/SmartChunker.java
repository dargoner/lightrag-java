package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SmartChunker implements Chunker {
    private static final Pattern FIGURE_OR_TABLE_LABEL_PATTERN = Pattern.compile(
        "^(?:图|表|附图|附表|figure|fig\\.?|table)\\s*[0-9一二三四五六七八九十]+(?:[.．-]\\s*[0-9]+)*(?:\\s*[:：]?\\s*.*)?$",
        Pattern.CASE_INSENSITIVE
    );

    private final SmartChunkerConfig config;
    private final SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer;
    private final StructuredDocumentParser structuredDocumentParser;
    private final SemanticChunkRefiner semanticChunkRefiner;
    private final ChunkTextSanitizer textSanitizer;
    private final AdaptiveChunkSizingPolicy adaptiveChunkSizingPolicy;

    public SmartChunker(SmartChunkerConfig config) {
        this(
            config,
            new SentenceBoundaryAnalyzer(),
            new StructuredDocumentParser(),
            new SemanticChunkRefiner(SemanticChunkRefiner.defaultSimilarity()),
            new ChunkTextSanitizer(),
            new AdaptiveChunkSizingPolicy()
        );
    }

    SmartChunker(
        SmartChunkerConfig config,
        SentenceBoundaryAnalyzer sentenceBoundaryAnalyzer,
        StructuredDocumentParser structuredDocumentParser,
        SemanticChunkRefiner semanticChunkRefiner,
        ChunkTextSanitizer textSanitizer,
        AdaptiveChunkSizingPolicy adaptiveChunkSizingPolicy
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.sentenceBoundaryAnalyzer = Objects.requireNonNull(sentenceBoundaryAnalyzer, "sentenceBoundaryAnalyzer");
        this.structuredDocumentParser = Objects.requireNonNull(structuredDocumentParser, "structuredDocumentParser");
        this.semanticChunkRefiner = Objects.requireNonNull(semanticChunkRefiner, "semanticChunkRefiner");
        this.textSanitizer = Objects.requireNonNull(textSanitizer, "textSanitizer");
        this.adaptiveChunkSizingPolicy = Objects.requireNonNull(adaptiveChunkSizingPolicy, "adaptiveChunkSizingPolicy");
    }

    @Override
    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isEmpty()) {
            return List.of();
        }

        return finalizeChunks(source, chunkStructural(source));
    }

    List<Chunk> chunkParsedDocument(ParsedDocument document, DocumentType documentType) {
        var source = Objects.requireNonNull(document, "document");
        Objects.requireNonNull(documentType, "documentType");
        if (source.plainText().isEmpty() && source.blocks().isEmpty()) {
            return List.of();
        }

        var draftSource = new Document(source.documentId(), source.title(), source.plainText(), source.metadata());
        var resolvedBlocks = resolveBlocks(source, documentType);
        if (resolvedBlocks.isEmpty()) {
            return finalizeChunks(draftSource, chunkStructural(draftSource));
        }
        var drafts = draftBlocks(source.title(), resolvedBlocks, documentType);
        return finalizeChunks(draftSource, buildChunks(draftSource, drafts));
    }

    List<Chunk> chunkStructural(Document document) {
        return chunkStructural(document, DocumentType.GENERIC);
    }

    List<Chunk> chunkStructural(Document document, DocumentType documentType) {
        var source = Objects.requireNonNull(document, "document");
        Objects.requireNonNull(documentType, "documentType");
        if (source.content().isEmpty()) {
            return List.of();
        }

        var structured = structuredDocumentParser.parse(source);
        var drafts = draftBlocks(source.title(), structured.blocks(), documentType);
        return buildChunks(source, drafts);
    }

    SmartChunkerConfig config() {
        return config;
    }

    private List<Chunk> finalizeChunks(Document source, List<Chunk> chunks) {
        return config.semanticMergeEnabled()
            ? semanticChunkRefiner.refine(source.id(), chunks, config.maxTokens(), config.semanticMergeThreshold())
            : chunks;
    }

    private List<StructuredBlock> resolveBlocks(ParsedDocument document, DocumentType documentType) {
        if (documentType == DocumentType.GENERIC && !document.blocks().isEmpty()) {
            var genericBlocks = normalizeGenericParsedBlocks(document);
            return genericBlocks.isEmpty() ? deriveFallbackBlocks(document) : genericBlocks;
        }
        var blocks = document.blocks().isEmpty()
            ? deriveFallbackBlocks(document)
            : normalizeParsedBlocks(document);
        return applyTemplate(blocks, documentType);
    }

    private List<StructuredBlock> deriveFallbackBlocks(ParsedDocument document) {
        return structuredDocumentParser.parse(new Document(
            document.documentId(),
            document.title(),
            document.plainText(),
            document.metadata()
        )).blocks();
    }

    private List<StructuredBlock> normalizeParsedBlocks(ParsedDocument document) {
        return document.blocks().stream()
            .sorted(Comparator.comparing(
                ParsedBlock::readingOrder,
                Comparator.nullsLast(Integer::compareTo)
            ))
            .map(block -> toStructuredBlock(block, document.title()))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<StructuredBlock> normalizeGenericParsedBlocks(ParsedDocument document) {
        var normalized = new ArrayList<StructuredBlock>();
        var currentSectionPath = fallbackSectionPath(document.title());
        var repeatedPageChromeTexts = textSanitizer.repeatedShortPageChromeTexts(document.blocks());
        for (var block : document.blocks().stream()
            .sorted(Comparator.comparing(
                ParsedBlock::readingOrder,
                Comparator.nullsLast(Integer::compareTo)
            ))
            .toList()) {
            var cleanedText = textSanitizer.sanitizeBlockText(block.text());
            if (cleanedText.isBlank()) {
                continue;
            }
            if (repeatedPageChromeTexts.contains(cleanedText)) {
                continue;
            }
            if (isHeadingBlock(block)) {
                currentSectionPath = resolveHeadingSectionPath(block, document.title(), cleanedText, currentSectionPath);
                continue;
            }
            normalized.addAll(splitGenericMixedBlock(new StructuredBlock(
                block.blockId(),
                mapType(block.blockType()),
                resolveGenericSectionPath(block, document.title(), currentSectionPath),
                cleanedText,
                Map.of()
            )));
        }
        return List.copyOf(normalized);
    }

    private List<StructuredBlock> applyTemplate(List<StructuredBlock> blocks, DocumentType documentType) {
        return switch (documentType) {
            case GENERIC, BOOK, LAW -> blocks;
            case QA -> combineQaBlocks(blocks);
        };
    }

    private List<StructuredBlock> combineQaBlocks(List<StructuredBlock> blocks) {
        var combined = new ArrayList<StructuredBlock>();
        int index = 0;
        while (index < blocks.size()) {
            var current = blocks.get(index);
            if (index + 1 < blocks.size()) {
                var next = blocks.get(index + 1);
                if (current.type() == StructuredBlock.Type.PARAGRAPH
                    && next.type() == StructuredBlock.Type.PARAGRAPH
                    && isQuestion(current.content())
                    && isAnswer(next.content())) {
                    combined.add(new StructuredBlock(
                        current.id() + "," + next.id(),
                        StructuredBlock.Type.PARAGRAPH,
                        current.sectionPath(),
                        current.content() + "\n" + next.content(),
                        Map.of()
                    ));
                    index += 2;
                    continue;
                }
            }
            combined.add(current);
            index++;
        }
        return List.copyOf(combined);
    }

    private static StructuredBlock.Type mapType(String blockType) {
        if (blockType == null) {
            return StructuredBlock.Type.PARAGRAPH;
        }
        return switch (blockType.strip().toLowerCase(Locale.ROOT)) {
            case "list" -> StructuredBlock.Type.LIST;
            case "table" -> StructuredBlock.Type.TABLE;
            default -> StructuredBlock.Type.PARAGRAPH;
        };
    }

    private StructuredBlock toStructuredBlock(ParsedBlock block, String title) {
        var cleanedText = textSanitizer.sanitizeBlockText(block.text());
        if (cleanedText.isBlank()) {
            return null;
        }
        return new StructuredBlock(
            block.blockId(),
            mapType(block.blockType()),
            resolveSectionPath(block, title),
            cleanedText,
            Map.of()
        );
    }

    private static String resolveSectionPath(ParsedBlock block, String title) {
        if (!block.sectionPath().isBlank()) {
            return block.sectionPath();
        }
        if (!block.sectionHierarchy().isEmpty()) {
            return String.join(" > ", block.sectionHierarchy());
        }
        return title == null || title.isBlank() ? "Untitled" : title;
    }

    private static String resolveGenericSectionPath(ParsedBlock block, String title, String currentSectionPath) {
        if (!block.sectionPath().isBlank()) {
            return block.sectionPath();
        }
        if (!block.sectionHierarchy().isEmpty()) {
            return String.join(" > ", block.sectionHierarchy());
        }
        return currentSectionPath == null || currentSectionPath.isBlank() ? fallbackSectionPath(title) : currentSectionPath;
    }

    private static String resolveHeadingSectionPath(ParsedBlock block, String title, String cleanedText, String currentSectionPath) {
        if (!block.sectionHierarchy().isEmpty()) {
            return String.join(" > ", block.sectionHierarchy());
        }
        if (!block.sectionPath().isBlank()) {
            return block.sectionPath();
        }
        if (!cleanedText.isBlank()) {
            return cleanedText;
        }
        return currentSectionPath == null || currentSectionPath.isBlank() ? fallbackSectionPath(title) : currentSectionPath;
    }

    private static boolean isHeadingBlock(ParsedBlock block) {
        var type = block.blockType().strip().toLowerCase(Locale.ROOT);
        return "title".equals(type) || "heading".equals(type) || "header".equals(type);
    }

    private static String fallbackSectionPath(String title) {
        return title == null || title.isBlank() ? "Untitled" : title;
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

    private List<ChunkDraft> draftBlocks(String documentTitle, List<StructuredBlock> blocks, DocumentType documentType) {
        var preparedBlocks = prepareBlocksForDrafting(blocks, documentTitle, documentType);
        var drafts = new ArrayList<ChunkDraft>();
        String previousSectionPath = "";
        for (var block : preparedBlocks) {
            drafts.addAll(chunkBlock(block, previousSectionPath, documentTitle, documentType));
            previousSectionPath = block.sectionPath();
        }
        return List.copyOf(drafts);
    }

    private List<StructuredBlock> prepareBlocksForDrafting(
        List<StructuredBlock> blocks,
        String documentTitle,
        DocumentType documentType
    ) {
        if (!supportsAdaptiveParagraphRegroup(documentType) || blocks.isEmpty()) {
            return List.copyOf(blocks);
        }

        var prepared = new ArrayList<StructuredBlock>(blocks.size());
        String previousSectionPath = "";
        int index = 0;
        while (index < blocks.size()) {
            var block = blocks.get(index);
            if (!isAdaptiveParagraphRegroupCandidate(block)) {
                prepared.add(block);
                previousSectionPath = block.sectionPath();
                index++;
                continue;
            }

            int runEnd = index + 1;
            while (runEnd < blocks.size()
                && isAdaptiveParagraphRegroupCandidate(blocks.get(runEnd))
                && Objects.equals(block.sectionPath(), blocks.get(runEnd).sectionPath())) {
                runEnd++;
            }

            var paragraphRun = blocks.subList(index, runEnd);
            prepared.addAll(regroupParagraphRun(paragraphRun, previousSectionPath, documentTitle, documentType));
            previousSectionPath = block.sectionPath();
            index = runEnd;
        }

        return List.copyOf(prepared);
    }

    private List<StructuredBlock> regroupParagraphRun(
        List<StructuredBlock> paragraphRun,
        String previousSectionPath,
        String documentTitle,
        DocumentType documentType
    ) {
        if (paragraphRun.size() <= 1) {
            return List.copyOf(paragraphRun);
        }

        var regrouped = new ArrayList<StructuredBlock>(paragraphRun.size());
        String regroupPreviousSectionPath = previousSectionPath;
        int index = 0;

        if (isHeadingTransition(paragraphRun.get(0).sectionPath(), regroupPreviousSectionPath, documentTitle)) {
            regrouped.add(paragraphRun.get(0));
            regroupPreviousSectionPath = paragraphRun.get(0).sectionPath();
            index = 1;
        }

        while (index < paragraphRun.size()) {
            if (!isSubstantiveRegroupParagraph(paragraphRun.get(index))) {
                regrouped.add(paragraphRun.get(index));
                regroupPreviousSectionPath = paragraphRun.get(index).sectionPath();
                index++;
                continue;
            }

            var group = new ArrayList<StructuredBlock>();
            group.add(paragraphRun.get(index));
            int next = index + 1;
            while (next < paragraphRun.size()) {
                if (!isSubstantiveRegroupParagraph(paragraphRun.get(next))) {
                    break;
                }
                var candidateGroup = new ArrayList<StructuredBlock>(group);
                candidateGroup.add(paragraphRun.get(next));
                var candidateContent = mergeParagraphContent(candidateGroup);
                var sizing = paragraphSizing(
                    sentenceBoundaryAnalyzer.split(candidateContent),
                    paragraphRun.get(index).sectionPath(),
                    regroupPreviousSectionPath,
                    documentTitle,
                    documentType
                );
                if (candidateContent.codePointCount(0, candidateContent.length()) > sizing.maxTokens()) {
                    break;
                }
                group = candidateGroup;
                next++;
            }
            regrouped.add(mergeParagraphGroup(group));
            regroupPreviousSectionPath = paragraphRun.get(index).sectionPath();
            index = next;
        }

        return List.copyOf(regrouped);
    }

    private List<ChunkDraft> chunkBlock(
        StructuredBlock block,
        String previousSectionPath,
        String documentTitle,
        DocumentType documentType
    ) {
        return switch (block.type()) {
            case PARAGRAPH -> buildParagraphDrafts(
                block.content(),
                block.sectionPath(),
                block.id(),
                previousSectionPath,
                documentTitle,
                documentType,
                block.metadata()
            );
            case CAPTION -> List.of(new ChunkDraft(block.content(), block.sectionPath(), "caption", block.id(), block.metadata()));
            case IMAGE -> List.of(new ChunkDraft(block.content(), block.sectionPath(), "image_placeholder", block.id(), block.metadata()));
            case LIST -> buildListDrafts(block);
            case TABLE -> buildTableDrafts(block);
        };
    }

    private List<ChunkDraft> buildParagraphDrafts(String content) {
        return buildParagraphDrafts(content, "", "paragraph:0", "", "", DocumentType.GENERIC, Map.of());
    }

    private List<ChunkDraft> buildParagraphDrafts(
        String content,
        String sectionPath,
        String blockId,
        String previousSectionPath,
        String documentTitle,
        DocumentType documentType,
        Map<String, String> extraMetadata
    ) {
        var sentences = sentenceBoundaryAnalyzer.split(content);
        var sizing = paragraphSizing(sentences, sectionPath, previousSectionPath, documentTitle, documentType);

        if (content.codePointCount(0, content.length()) <= sizing.maxTokens()) {
            return List.of(new ChunkDraft(content.strip(), sectionPath, "text", blockId, extraMetadata));
        }

        if (sentences.stream().anyMatch(sentence -> sentence.codePointCount(0, sentence.length()) > sizing.maxTokens())) {
            return fallbackChunks(content, sectionPath, blockId, "text", extraMetadata, sizing);
        }
        if (sentences.size() <= 1) {
            return fallbackChunks(content, sectionPath, blockId, "text", extraMetadata, sizing);
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
                if (!selected.isEmpty() && tokenCount + candidateTokens > sizing.maxTokens()) {
                    break;
                }
                selected.add(candidate);
                tokenCount += candidateTokens;
                endExclusive++;
                if (tokenCount >= sizing.targetTokens()) {
                    break;
                }
            }
            if (selected.isEmpty()) {
                return fallbackChunks(content, sectionPath, blockId, "text", extraMetadata, sizing);
            }
            chunks.add(new ChunkDraft(String.join(" ", selected), sectionPath, "text", blockId, extraMetadata));
            if (endExclusive >= sentences.size()) {
                break;
            }
            int nextStart = rewindStart(start, endExclusive, sentences, sizing.overlapTokens());
            if (nextStart <= start) {
                nextStart = Math.min(endExclusive, sentences.size());
            }
            start = nextStart;
        }
        return List.copyOf(chunks);
    }

    private AdaptiveChunkSizingPolicy.AdaptiveSizing paragraphSizing(
        List<String> sentences,
        String sectionPath,
        String previousSectionPath,
        String documentTitle,
        DocumentType documentType
    ) {
        boolean headingTransition = isHeadingTransition(sectionPath, previousSectionPath, documentTitle);
        int averageSentenceTokens = sentences.isEmpty()
            ? 0
            : (int) Math.round(
            sentences.stream()
                .mapToInt(sentence -> sentence.codePointCount(0, sentence.length()))
                .average()
                .orElse(0.0d)
        );
        return adaptiveChunkSizingPolicy.resolve(
            config,
            documentType,
            StructuredBlock.Type.PARAGRAPH,
            headingTransition,
            sentences.size(),
            averageSentenceTokens
        );
    }

    private List<ChunkDraft> buildListDrafts(StructuredBlock block) {
        if (block.content().codePointCount(0, block.content().length()) <= config.maxTokens()) {
            return List.of(new ChunkDraft(block.content(), block.sectionPath(), "list", block.id(), block.metadata()));
        }
        var lines = List.of(block.content().split("\\R"));
        if (lines.stream().anyMatch(line -> isListItem(line) && line.codePointCount(0, line.length()) > config.maxTokens())) {
            return fallbackChunks(block.content(), block.sectionPath(), block.id(), "list", block.metadata(), baseSizing());
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        var lead = isListItem(lines.get(0)) ? "" : lines.get(0);
        if (!lead.isEmpty() && lead.codePointCount(0, lead.length()) > config.maxTokens()) {
            return fallbackChunks(block.content(), block.sectionPath(), block.id(), "list", block.metadata(), baseSizing());
        }
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
                drafts.add(new ChunkDraft(String.join("\n", current), block.sectionPath(), "list", block.id(), block.metadata()));
                current = new ArrayList<String>();
                if (!lead.isEmpty()) {
                    current.add(lead);
                }
                candidate = current.isEmpty() ? item : String.join("\n", current) + "\n" + item;
                if (candidate.codePointCount(0, candidate.length()) > config.maxTokens()) {
                    return fallbackChunks(block.content(), block.sectionPath(), block.id(), "list", block.metadata(), baseSizing());
                }
            }
            current.add(item);
        }
        if (!current.isEmpty()) {
            drafts.add(new ChunkDraft(String.join("\n", current), block.sectionPath(), "list", block.id(), block.metadata()));
        }
        return List.copyOf(drafts);
    }

    private List<ChunkDraft> buildTableDrafts(StructuredBlock block) {
        var lines = List.of(block.content().split("\\R"));
        if (block.content().codePointCount(0, block.content().length()) <= config.maxTokens()) {
            return List.of(new ChunkDraft(
                block.content(),
                block.sectionPath(),
                "table",
                block.id(),
                mergeMetadata(
                    block.metadata(),
                    Map.of(
                        SmartChunkMetadata.TABLE_PART_INDEX, "1",
                        SmartChunkMetadata.TABLE_PART_TOTAL, "1"
                    )
                )
            ));
        }
        if (lines.size() <= 2) {
            return fallbackChunks(block.content(), block.sectionPath(), block.id(), "table", block.metadata(), baseSizing());
        }
        var header = lines.get(0) + "\n" + lines.get(1);
        var rows = lines.subList(2, lines.size());
        for (var row : rows) {
            var rowWithHeader = header + "\n" + row;
            if (rowWithHeader.codePointCount(0, rowWithHeader.length()) > config.maxTokens()) {
                return fallbackChunks(block.content(), block.sectionPath(), block.id(), "table", block.metadata(), baseSizing());
            }
        }
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
                mergeMetadata(
                    block.metadata(),
                    Map.of(
                    SmartChunkMetadata.TABLE_PART_INDEX, Integer.toString(index + 1),
                    SmartChunkMetadata.TABLE_PART_TOTAL, Integer.toString(chunkTexts.size())
                    )
                )
            ));
        }
        return List.copyOf(drafts);
    }

    private int rewindStart(int start, int endExclusive, List<String> sentences, int overlapTokenBudget) {
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

    private List<ChunkDraft> fallbackChunks(
        String content,
        String sectionPath,
        String blockId,
        String contentType,
        Map<String, String> extraMetadata,
        AdaptiveChunkSizingPolicy.AdaptiveSizing sizing
    ) {
        int overlap = Math.min(sizing.overlapTokens(), Math.max(0, sizing.maxTokens() - 1));
        return new FixedWindowChunker(sizing.maxTokens(), overlap).chunk(new Document("fallback", "", content, Map.of())).stream()
            .map(Chunk::text)
            .map(text -> new ChunkDraft(text, sectionPath, contentType, blockId, extraMetadata))
            .toList();
    }

    private AdaptiveChunkSizingPolicy.AdaptiveSizing baseSizing() {
        return AdaptiveChunkSizingPolicy.AdaptiveSizing.fromConfig(config);
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

    private static Map<String, String> mergeMetadata(Map<String, String> base, Map<String, String> overlay) {
        if (base.isEmpty()) {
            return overlay;
        }
        if (overlay.isEmpty()) {
            return base;
        }
        var merged = new LinkedHashMap<String, String>(base);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    private static boolean isListItem(String line) {
        return line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") || line.matches("^\\d+[.)]\\s+.+$");
    }

    private static boolean isQuestion(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("q:")
            || normalized.startsWith("q.")
            || normalized.startsWith("question:")
            || normalized.startsWith("问：")
            || normalized.startsWith("问:");
    }

    private static boolean isAnswer(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("a:")
            || normalized.startsWith("a.")
            || normalized.startsWith("answer:")
            || normalized.startsWith("答：")
            || normalized.startsWith("答:");
    }

    private boolean supportsAdaptiveParagraphRegroup(DocumentType documentType) {
        return config.adaptiveChunkingEnabled()
            && (documentType == DocumentType.GENERIC || documentType == DocumentType.BOOK);
    }

    private static boolean isAdaptiveParagraphRegroupCandidate(StructuredBlock block) {
        return block.type() == StructuredBlock.Type.PARAGRAPH;
    }

    private static List<StructuredBlock> splitGenericMixedBlock(StructuredBlock block) {
        if (block.type() != StructuredBlock.Type.PARAGRAPH) {
            return List.of(block);
        }
        var segments = segmentMixedParagraphContent(block.content());
        if (segments.size() <= 1) {
            return List.of(block);
        }
        var split = new ArrayList<StructuredBlock>(segments.size());
        int textIndex = 0;
        int imageIndex = 0;
        int captionIndex = 0;
        String previousImagePath = null;
        for (var segment : segments) {
            switch (segment.type()) {
                case TEXT -> split.add(new StructuredBlock(
                    block.id() + "#text-" + (++textIndex),
                    StructuredBlock.Type.PARAGRAPH,
                    block.sectionPath(),
                    segment.content(),
                    Map.of()
                ));
                case IMAGE -> split.add(new StructuredBlock(
                    block.id() + "#image-" + (++imageIndex),
                    StructuredBlock.Type.IMAGE,
                    block.sectionPath(),
                    segment.content(),
                    imageMetadata(segment.content(), "self")
                ));
                case CAPTION -> split.add(new StructuredBlock(
                    block.id() + "#caption-" + (++captionIndex),
                    StructuredBlock.Type.CAPTION,
                    block.sectionPath(),
                    segment.content(),
                    previousImagePath == null ? Map.of() : imageMetadata(previousImagePath, "adjacent_caption")
                ));
            }
            previousImagePath = switch (segment.type()) {
                case IMAGE -> segment.content();
                case TEXT, CAPTION -> null;
            };
        }
        return List.copyOf(split);
    }

    private static Map<String, String> imageMetadata(String imagePath, String refMode) {
        if (imagePath == null || imagePath.isBlank()) {
            return Map.of();
        }
        return Map.of(
            SmartChunkMetadata.PRIMARY_IMAGE_PATH, imagePath,
            SmartChunkMetadata.IMAGE_PATHS, imagePath,
            SmartChunkMetadata.IMAGE_REF_MODE, refMode
        );
    }

    private static List<MixedContentSegment> segmentMixedParagraphContent(String content) {
        var segments = new ArrayList<MixedContentSegment>();
        var proseLines = new ArrayList<String>();
        for (var rawLine : content.split("\\R")) {
            var line = rawLine.strip();
            if (line.isEmpty()) {
                if (!proseLines.isEmpty() && !proseLines.get(proseLines.size() - 1).isEmpty()) {
                    proseLines.add("");
                }
                continue;
            }
            if (isImagePlaceholderLine(line)) {
                flushProseSegment(segments, proseLines);
                segments.addAll(splitImageLeadingLine(line));
                continue;
            }
            if (looksLikeFigureOrTableLabel(line)) {
                flushProseSegment(segments, proseLines);
                segments.add(new MixedContentSegment(MixedContentSegmentType.CAPTION, line));
                continue;
            }
            proseLines.add(line);
        }
        flushProseSegment(segments, proseLines);
        return List.copyOf(segments);
    }

    private static void flushProseSegment(List<MixedContentSegment> segments, List<String> proseLines) {
        if (proseLines.isEmpty()) {
            return;
        }
        int end = proseLines.size();
        while (end > 0 && proseLines.get(end - 1).isEmpty()) {
            end--;
        }
        if (end == 0) {
            proseLines.clear();
            return;
        }
        var prose = String.join("\n", proseLines.subList(0, end)).strip();
        proseLines.clear();
        if (!prose.isEmpty()) {
            segments.add(new MixedContentSegment(MixedContentSegmentType.TEXT, prose));
        }
    }

    private static boolean isImagePlaceholderLine(String line) {
        return line.startsWith("images/");
    }

    private static List<MixedContentSegment> splitImageLeadingLine(String line) {
        var segments = new ArrayList<MixedContentSegment>();
        var remaining = line.strip();
        while (remaining.startsWith("images/")) {
            int separator = findFirstWhitespace(remaining);
            if (separator < 0) {
                segments.add(new MixedContentSegment(MixedContentSegmentType.IMAGE, remaining));
                return List.copyOf(segments);
            }
            var imagePath = remaining.substring(0, separator).strip();
            if (!imagePath.isEmpty()) {
                segments.add(new MixedContentSegment(MixedContentSegmentType.IMAGE, imagePath));
            }
            remaining = remaining.substring(separator).strip();
            if (remaining.isEmpty()) {
                return List.copyOf(segments);
            }
            if (looksLikeFigureOrTableLabel(remaining)) {
                segments.add(new MixedContentSegment(MixedContentSegmentType.CAPTION, remaining));
                return List.copyOf(segments);
            }
        }
        if (!remaining.isEmpty()) {
            segments.add(new MixedContentSegment(
                looksLikeFigureOrTableLabel(remaining) ? MixedContentSegmentType.CAPTION : MixedContentSegmentType.TEXT,
                remaining
            ));
        }
        return List.copyOf(segments);
    }

    private static int findFirstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private boolean isSubstantiveRegroupParagraph(StructuredBlock block) {
        var content = block.content();
        return content.codePointCount(0, content.length()) >= regroupMinParagraphTokens()
            && !looksLikeLowConfidenceNonProse(content);
    }

    private int regroupMinParagraphTokens() {
        int relaxedThreshold = 14 - (int) Math.round(Math.min(config.targetTokens(), 120) / 20.0d);
        return Math.max(8, Math.min(14, relaxedThreshold));
    }

    private static boolean looksLikeLowConfidenceNonProse(String text) {
        var normalized = text.strip();
        if (normalized.isEmpty()) {
            return true;
        }
        if (looksLikeFigureOrTableLabel(normalized)) {
            return true;
        }
        int length = normalized.codePointCount(0, normalized.length());
        int sentencePunctuationCount = countSentencePunctuation(normalized);
        if (sentencePunctuationCount > 0 || endsWithSentencePunctuation(normalized) || looksLikeLongClauseDenseProse(normalized)) {
            return false;
        }
        if (!containsInlineClausePunctuation(normalized) && length <= 20) {
            return true;
        }
        return whitespaceRatio(normalized) >= 0.20d && length <= 40;
    }

    private static boolean looksLikeFigureOrTableLabel(String text) {
        var normalized = text.strip();
        if (FIGURE_OR_TABLE_LABEL_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        for (var line : normalized.split("\\R")) {
            var stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("images/")) {
                continue;
            }
            return FIGURE_OR_TABLE_LABEL_PATTERN.matcher(stripped).matches();
        }
        return false;
    }

    private static boolean endsWithSentencePunctuation(String text) {
        return text.endsWith("。")
            || text.endsWith("！")
            || text.endsWith("？")
            || text.endsWith(".")
            || text.endsWith("!")
            || text.endsWith("?");
    }

    private static boolean containsInlineClausePunctuation(String text) {
        return text.contains("，")
            || text.contains(",")
            || text.contains("；")
            || text.contains(";")
            || text.contains("：")
            || text.contains(":");
    }

    private static boolean looksLikeLongClauseDenseProse(String text) {
        return text.codePointCount(0, text.length()) >= 24
            && containsInlineClausePunctuation(text)
            && !looksLikeFigureOrTableLabel(text);
    }

    private static int countSentencePunctuation(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?') {
                count++;
            }
        }
        return count;
    }

    private static double whitespaceRatio(String text) {
        int whitespace = 0;
        int total = text.codePointCount(0, text.length());
        for (int index = 0; index < text.length(); index++) {
            if (Character.isWhitespace(text.charAt(index))) {
                whitespace++;
            }
        }
        return total == 0 ? 0.0d : (double) whitespace / total;
    }

    private static boolean isHeadingTransition(String sectionPath, String previousSectionPath, String documentTitle) {
        return !sectionPath.isBlank()
            && (
            (!previousSectionPath.isBlank() && !sectionPath.equals(previousSectionPath))
                || (previousSectionPath.isBlank() && !sectionPath.equals(fallbackSectionPath(documentTitle)))
        );
    }

    private static StructuredBlock mergeParagraphGroup(List<StructuredBlock> group) {
        if (group.size() == 1) {
            return group.get(0);
        }
        var first = group.get(0);
        return new StructuredBlock(
            group.stream().map(StructuredBlock::id).collect(java.util.stream.Collectors.joining(",")),
            StructuredBlock.Type.PARAGRAPH,
            first.sectionPath(),
            mergeParagraphContent(group),
            first.metadata()
        );
    }

    private static String mergeParagraphContent(List<StructuredBlock> group) {
        return group.stream()
            .map(StructuredBlock::content)
            .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private record ChunkDraft(
        String text,
        String sectionPath,
        String contentType,
        String sourceBlockId,
        Map<String, String> extraMetadata
    ) {
    }

    private enum MixedContentSegmentType {
        TEXT,
        IMAGE,
        CAPTION
    }

    private record MixedContentSegment(MixedContentSegmentType type, String content) {
    }
}
