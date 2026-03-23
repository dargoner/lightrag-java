package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SmartChunkerTest {
    @Test
    void splitsOnSentenceBoundariesAndKeepsSentenceAwareOverlap() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(20)
            .maxTokens(25)
            .overlapTokens(10)
            .build());
        var document = new Document("doc-1", "Guide", "Alpha one. Beta two. Gamma three.", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly(
                "Alpha one. Beta two.",
                "Beta two. Gamma three."
            );
        assertThat(chunks)
            .extracting(Chunk::order)
            .containsExactly(0, 1);
    }

    @Test
    void adaptiveChunkingMakesLongGenericParagraphsCoarser() {
        var adaptiveChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(100)
            .maxTokens(120)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(true)
            .build());
        var fixedChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(100)
            .maxTokens(120)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(false)
            .build());
        var document = new Document(
            "doc-adaptive-generic",
            "Guide",
            """
            Data platforms improve retrieval precision for enterprise search teams. Data platforms improve retrieval precision for enterprise search teams.
            Data pipelines stabilize entity extraction during index refresh windows. Data pipelines stabilize entity extraction during index refresh windows.
            Semantic ranking reduces noisy recall for dense narrative documents. Semantic ranking reduces noisy recall for dense narrative documents.
            Chunk metadata preserves section context for downstream answer citation. Chunk metadata preserves section context for downstream answer citation.
            """,
            Map.of()
        );

        var adaptiveChunks = adaptiveChunker.chunk(document);
        var fixedChunks = fixedChunker.chunk(document);

        assertThat(adaptiveChunks).hasSizeLessThan(fixedChunks.size());
    }

    @Test
    void adaptiveChunkingKeepsHeadingTransitionParagraphsFiner() {
        var adaptiveChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(100)
            .maxTokens(130)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(true)
            .build());
        var fixedChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(100)
            .maxTokens(130)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(false)
            .build());
        var document = new Document(
            "doc-adaptive-heading",
            "Guide",
            """
            # Overview
            Headings improve retrieval precision. Headings improve retrieval precision. Headings improve retrieval precision. Headings improve retrieval precision.
            """,
            Map.of()
        );

        var adaptiveChunks = adaptiveChunker.chunk(document);
        var fixedChunks = fixedChunker.chunk(document);

        assertThat(adaptiveChunks.get(0).tokenCount()).isLessThan(fixedChunks.get(0).tokenCount());
    }

    @Test
    void preservesSourceMetadataAndAddsSmartChunkMetadata() {
        var chunker = new SmartChunker(SmartChunkerConfig.defaults());
        var document = new Document(
            "doc-1",
            "Guide",
            "Only one sentence.",
            Map.of(
                "source", "guide.md",
                "file_path", "/docs/guide.md"
            )
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata())
            .containsEntry("source", "guide.md")
            .containsEntry("file_path", "/docs/guide.md")
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Guide")
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "text")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "paragraph:0");
    }

    @Test
    void consumesParsedBlocksWhenStructuredMineruBlocksAreAvailable() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-structured",
            "Guide",
            "PLAIN TEXT SHOULD NOT WIN",
            List.of(
                new ParsedBlock("block-1", "paragraph", "Structured alpha", "Chapter 1", List.of("Chapter 1"), 1, null, 1, Map.of()),
                new ParsedBlock("block-2", "paragraph", "Structured beta", "Chapter 1", List.of("Chapter 1"), 1, null, 2, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.BOOK);

        assertThat(chunks).hasSize(2);
        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("Structured alpha", "Structured beta");
        assertThat(chunks)
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry("source", "mineru")
                .containsEntry(SmartChunkMetadata.SECTION_PATH, "Chapter 1"));
    }

    @Test
    void splitsMixedParagraphBlockIntoTextImageAndCaptionChunks() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(320)
            .maxTokens(400)
            .overlapTokens(20)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.2d)
            .build());
        var document = new ParsedDocument(
            "doc-mixed-block",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock(
                    "p-1",
                    "paragraph",
                    """
                    2014年至今，国家先后从医院信息化、CIS、区域信息化等层面进行政策改革，具体如下图所示。

                    images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg
                    图 1-4：历次医疗改革中医疗信息化政策重点一览图
                    """,
                    "1.2.1 医疗产业政策",
                    List.of("1.2.1 医疗产业政策"),
                    1,
                    null,
                    1,
                    Map.of()
                ),
                new ParsedBlock("p-2", "paragraph", "后续治理流程逐步固化。", "1.2.1 医疗产业政策", List.of("1.2.1 医疗产业政策"), 1, null, 2, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks).extracting(Chunk::text).containsExactly(
            "2014年至今，国家先后从医院信息化、CIS、区域信息化等层面进行政策改革，具体如下图所示。",
            "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg",
            "图 1-4：历次医疗改革中医疗信息化政策重点一览图",
            "后续治理流程逐步固化。"
        );
        assertThat(chunks).extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.CONTENT_TYPE)).containsExactly(
            "text",
            "image_placeholder",
            "caption",
            "text"
        );
        assertThat(chunks).extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.SOURCE_BLOCK_IDS)).containsExactly(
            "p-1#text-1",
            "p-1#image-1",
            "p-1#caption-1",
            "p-2"
        );
        assertThat(chunks.get(0).metadata())
            .doesNotContainKeys(SmartChunkMetadata.PRIMARY_IMAGE_PATH, SmartChunkMetadata.IMAGE_PATHS);
        assertThat(chunks.get(1).metadata())
            .containsEntry(SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_PATHS, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_REF_MODE, "self");
        assertThat(chunks.get(2).metadata())
            .containsEntry(SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_PATHS, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_REF_MODE, "adjacent_caption");
        assertThat(chunks.get(3).metadata())
            .doesNotContainKeys(SmartChunkMetadata.PRIMARY_IMAGE_PATH, SmartChunkMetadata.IMAGE_PATHS);
    }

    @Test
    void splitsInlineImageAndCaptionLineIntoSeparateChunks() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(320)
            .maxTokens(400)
            .overlapTokens(20)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.2d)
            .build());
        var document = new ParsedDocument(
            "doc-inline-image-caption",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock(
                    "p-1",
                    "paragraph",
                    """
                    2014年至今，国家先后从医院信息化、CIS、区域信息化等层面进行政策改革，具体如下图所示。

                    images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg 图 1-4：历次医疗改革中医疗信息化政策重点一览图
                    """,
                    "1.2.1 医疗产业政策",
                    List.of("1.2.1 医疗产业政策"),
                    1,
                    null,
                    1,
                    Map.of()
                )
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks).extracting(Chunk::text).containsExactly(
            "2014年至今，国家先后从医院信息化、CIS、区域信息化等层面进行政策改革，具体如下图所示。",
            "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg",
            "图 1-4：历次医疗改革中医疗信息化政策重点一览图"
        );
        assertThat(chunks).extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.CONTENT_TYPE)).containsExactly(
            "text",
            "image_placeholder",
            "caption"
        );
        assertThat(chunks.get(0).metadata())
            .doesNotContainKeys(SmartChunkMetadata.PRIMARY_IMAGE_PATH, SmartChunkMetadata.IMAGE_PATHS);
        assertThat(chunks.get(1).metadata())
            .containsEntry(SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_PATHS, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_REF_MODE, "self");
        assertThat(chunks.get(2).metadata())
            .containsEntry(SmartChunkMetadata.PRIMARY_IMAGE_PATH, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_PATHS, "images/9bb15373cffbba23c1ed96f3f20bdf3128ec142fe7b4a363511985cd460895d6.jpg")
            .containsEntry(SmartChunkMetadata.IMAGE_REF_MODE, "adjacent_caption");
    }

    @Test
    void usesHeadingHierarchyAsSectionPath() {
        var chunker = new SmartChunker(SmartChunkerConfig.defaults());
        var document = new Document(
            "doc-2",
            "Guide",
            "# Policies\n## Travel\nCarry your passport.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Policies > Travel");
    }

    @Test
    void keepsLeadSentenceWithListItems() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(64)
            .maxTokens(64)
            .overlapTokens(8)
            .build());
        var document = new Document(
            "doc-3",
            "Guide",
            "# Rules\nExceptions:\n- War\n- Flood",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("Exceptions:").contains("- War").contains("- Flood");
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "list")
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "Rules");
    }

    @Test
    void repeatsTableHeaderWhenTableIsSplit() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(40)
            .maxTokens(40)
            .overlapTokens(8)
            .build());
        var document = new Document(
            "doc-4",
            "Guide",
            "# Prices\n| Name | Cost |\n| --- | --- |\n| A | 1 |\n| B | 2 |\n| C | 3 |\n| D | 4 |",
            Map.of("source", "prices.md")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks)
            .extracting(Chunk::text)
            .allSatisfy(text -> assertThat(text).startsWith("| Name | Cost |\n| --- | --- |"));
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.CONTENT_TYPE, "table")
            .containsEntry("smart_chunker.table_part_index", "1")
            .containsEntry("smart_chunker.prev_chunk_id", "");
        assertThat(chunks.get(1).metadata())
            .containsEntry("smart_chunker.table_part_index", "2")
            .containsEntry("smart_chunker.prev_chunk_id", "doc-4:0");
    }

    @Test
    void mergesAdjacentParagraphsWhenSemanticRefinementIsEnabled() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.2d)
            .build());
        var document = new Document(
            "doc-5",
            "Guide",
            "Retrieval pipeline overview.\n\nRetrieval pipeline details.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
            .contains("Retrieval pipeline overview.")
            .contains("Retrieval pipeline details.");
    }

    @Test
    void lawTemplateDoesNotMergeAcrossArticles() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.2d)
            .build());
        var document = new ParsedDocument(
            "doc-law",
            "Regulation",
            "ignored",
            List.of(
                new ParsedBlock("law-1", "paragraph", "第一条 检索规则。", "第一条", List.of("第一条"), 1, null, 1, Map.of()),
                new ParsedBlock("law-2", "paragraph", "第二条 检索规则。", "第二条", List.of("第二条"), 1, null, 2, Map.of())
            ),
            Map.of()
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.LAW);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).metadata()).containsEntry(SmartChunkMetadata.SECTION_PATH, "第一条");
        assertThat(chunks.get(1).metadata()).containsEntry(SmartChunkMetadata.SECTION_PATH, "第二条");
    }

    @Test
    void genericParsedDocumentUsesTitleAsSectionContextWithoutEmittingStandaloneHeadingChunk() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-generic",
            "白皮书",
            "忽略这段 plainText",
            List.of(
                new ParsedBlock("title-1", "title", "第二章 数据要素", "第二章 数据要素", List.of("第二章 数据要素"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("noise-1", "paragraph", "text_list text", "第二章 数据要素", List.of("第二章 数据要素"), 1, null, 2, Map.of()),
                new ParsedBlock("p-1", "paragraph", "数据基础设施是重点。", "第二章 数据要素", List.of("第二章 数据要素"), 1, null, 3, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("数据基础设施是重点。");
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "第二章 数据要素")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "p-1");
    }

    @Test
    void genericParsedDocumentFallsBackToPlainTextWhenAllStructuredBlocksAreNoise() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-generic-fallback",
            "白皮书",
            "这是真正需要切分的正文。",
            List.of(
                new ParsedBlock("title-1", "title", "目 录", "", List.of(), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("noise-1", "paragraph", "- 1 -", "", List.of(), 1, null, 2, Map.of()),
                new ParsedBlock("noise-2", "paragraph", "text_list text", "", List.of(), 1, null, 3, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("这是真正需要切分的正文。");
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "白皮书")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "paragraph:0");
    }

    @Test
    void genericParsedDocumentDropsRepeatedShortPageHeadersAcrossPages() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-generic-header",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第一章 总论", "第一章 总论", List.of("第一章 总论"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("header-1", "paragraph", "中国数字经济发展白皮书", "", List.of(), 1, null, 2, Map.of()),
                new ParsedBlock("p-1", "paragraph", "正文第一页。", "", List.of(), 1, null, 3, Map.of()),
                new ParsedBlock("header-2", "paragraph", "中国数字经济发展白皮书", "", List.of(), 2, null, 4, Map.of()),
                new ParsedBlock("p-2", "paragraph", "正文第二页。", "", List.of(), 2, null, 5, Map.of()),
                new ParsedBlock("header-3", "paragraph", "中国数字经济发展白皮书", "", List.of(), 3, null, 6, Map.of()),
                new ParsedBlock("p-3", "paragraph", "正文第三页。", "", List.of(), 3, null, 7, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("正文第一页。", "正文第二页。", "正文第三页。");
        assertThat(chunks)
            .allSatisfy(chunk -> assertThat(chunk.text()).doesNotContain("中国数字经济发展白皮书"));
    }

    @Test
    void genericParsedDocumentKeepsRepeatedShortBodySentenceWhenItLooksLikeRealSentence() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-generic-keep",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("p-1", "paragraph", "附件说明。", "", List.of(), 1, null, 1, Map.of()),
                new ParsedBlock("p-2", "paragraph", "正文第二页。", "", List.of(), 2, null, 2, Map.of()),
                new ParsedBlock("p-3", "paragraph", "附件说明。", "", List.of(), 3, null, 3, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("附件说明。", "正文第二页。", "附件说明。");
    }

    @Test
    void adaptiveChunkingRegroupsShortParagraphsWithinSameGenericSection() {
        var adaptiveChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(24)
            .maxTokens(48)
            .overlapTokens(6)
            .adaptiveChunkingEnabled(true)
            .build());
        var fixedChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(24)
            .maxTokens(48)
            .overlapTokens(6)
            .adaptiveChunkingEnabled(false)
            .build());
        var document = new ParsedDocument(
            "doc-generic-regroup",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第二章 数据基础设施", "第二章 数据基础设施", List.of("第二章 数据基础设施"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("p-1", "paragraph", "数据底座建设需要统一资源编排。", "第二章 数据基础设施", List.of("第二章 数据基础设施"), 1, null, 2, Map.of()),
                new ParsedBlock("p-2", "paragraph", "算力调度平台需要稳定监控指标。", "第二章 数据基础设施", List.of("第二章 数据基础设施"), 1, null, 3, Map.of()),
                new ParsedBlock("p-3", "paragraph", "数据治理流程需要沉淀标准接口。", "第二章 数据基础设施", List.of("第二章 数据基础设施"), 1, null, 4, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var adaptiveChunks = adaptiveChunker.chunkParsedDocument(document, DocumentType.GENERIC);
        var fixedChunks = fixedChunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(fixedChunks).hasSize(3);
        assertThat(adaptiveChunks).hasSizeLessThan(fixedChunks.size());
        assertThat(adaptiveChunks)
            .anySatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "p-2,p-3"));
    }

    @Test
    void adaptiveParagraphRegroupThresholdBecomesLooserAtCoarserGranularity() {
        var fineChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(24)
            .maxTokens(48)
            .overlapTokens(6)
            .adaptiveChunkingEnabled(true)
            .build());
        var coarseChunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(180)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(true)
            .build());
        var document = new ParsedDocument(
            "doc-generic-granularity-regroup",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第三章 平台能力", "第三章 平台能力", List.of("第三章 平台能力"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("p-1", "paragraph", "平台建设持续推进。", "第三章 平台能力", List.of("第三章 平台能力"), 1, null, 2, Map.of()),
                new ParsedBlock("p-2", "paragraph", "调度体系稳定运行。", "第三章 平台能力", List.of("第三章 平台能力"), 1, null, 3, Map.of()),
                new ParsedBlock("p-3", "paragraph", "治理流程逐步固化。", "第三章 平台能力", List.of("第三章 平台能力"), 1, null, 4, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var fineChunks = fineChunker.chunkParsedDocument(document, DocumentType.GENERIC);
        var coarseChunks = coarseChunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(fineChunks).hasSize(3);
        assertThat(coarseChunks).hasSizeLessThan(fineChunks.size());
        assertThat(coarseChunks)
            .anySatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "p-2,p-3"));
    }

    @Test
    void adaptiveRegroupSkipsFigureCaptionLikeParagraphsEvenWhenTheyAreLongEnough() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(180)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(true)
            .build());
        var document = new ParsedDocument(
            "doc-generic-caption-guard",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第四章 能力体系", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("p-1", "paragraph", "平台能力建设持续推进。", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 2, Map.of()),
                new ParsedBlock("p-2", "paragraph", "图 1 平台能力总体框架", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 3, Map.of()),
                new ParsedBlock("p-3", "paragraph", "治理流程逐步固化。", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 4, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("平台能力建设持续推进。", "图 1 平台能力总体框架", "治理流程逐步固化。");
        assertThat(chunks)
            .extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.SOURCE_BLOCK_IDS))
            .containsExactly("p-1", "p-2", "p-3");
    }

    @Test
    void adaptiveRegroupSkipsImagePrefixedFigureCaptionParagraphs() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(320)
            .maxTokens(400)
            .overlapTokens(20)
            .adaptiveChunkingEnabled(true)
            .build());
        var document = new ParsedDocument(
            "doc-generic-image-caption-guard",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第四章 能力体系", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock(
                    "p-1",
                    "paragraph",
                    "5G 智能医疗终端利用 5G 网络通信技术，与物联网传感技术、云计算技术相结合，将健康信息大数据化，将健康数据和落地服务有效结合，提供一种新型的健康管理模式。",
                    "第四章 能力体系",
                    List.of("第四章 能力体系"),
                    1,
                    null,
                    2,
                    Map.of()
                ),
                new ParsedBlock(
                    "p-2",
                    "paragraph",
                    """
                    images/f2a11930e08134c8a17bd3a9ef1b6bbb34ba930e5bba4d634e777447abab6e60.jpg

                    图 3-2：智能化医疗终端
                    """,
                    "第四章 能力体系",
                    List.of("第四章 能力体系"),
                    1,
                    null,
                    3,
                    Map.of()
                ),
                new ParsedBlock("p-3", "paragraph", "治理流程逐步固化。", "第四章 能力体系", List.of("第四章 能力体系"), 1, null, 4, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks)
            .extracting(chunk -> chunk.metadata().get(SmartChunkMetadata.SOURCE_BLOCK_IDS))
            .containsExactly("p-1", "p-2#image-1", "p-2#caption-1", "p-3");
    }

    @Test
    void adaptiveRegroupStillAcceptsLongOcrStyleProseWithoutFinalSentencePunctuation() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(220)
            .overlapTokens(12)
            .adaptiveChunkingEnabled(true)
            .build());
        var document = new ParsedDocument(
            "doc-generic-ocr-prose",
            "白皮书",
            "ignored",
            List.of(
                new ParsedBlock("title-1", "title", "第五章 发展趋势", "第五章 发展趋势", List.of("第五章 发展趋势"), 1, null, 1, Map.of("level", "1")),
                new ParsedBlock("p-1", "paragraph", "数字化转型已经进入纵深推进阶段，平台能力和治理能力需要同步建设", "第五章 发展趋势", List.of("第五章 发展趋势"), 1, null, 2, Map.of()),
                new ParsedBlock("p-2", "paragraph", "产业协同、数据流通、算力调度正在加速融合，新的基础设施供给模式逐步形成", "第五章 发展趋势", List.of("第五章 发展趋势"), 1, null, 3, Map.of()),
                new ParsedBlock("p-3", "paragraph", "平台供给、治理协同、应用落地之间的联动效应持续增强，新的增长空间加快释放", "第五章 发展趋势", List.of("第五章 发展趋势"), 1, null, 4, Map.of())
            ),
            Map.of("source", "mineru")
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.GENERIC);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "p-1");
        assertThat(chunks.get(1).metadata())
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "p-2,p-3");
    }

    @Test
    void qaTemplateKeepsQuestionAndAnswerTogether() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .build());
        var document = new ParsedDocument(
            "doc-qa",
            "FAQ",
            "ignored",
            List.of(
                new ParsedBlock("q-1", "paragraph", "Q: 支持什么文件？", "FAQ", List.of("FAQ"), 1, null, 1, Map.of()),
                new ParsedBlock("a-1", "paragraph", "A: 支持 PDF 和 Word。", "FAQ", List.of("FAQ"), 1, null, 2, Map.of())
            ),
            Map.of()
        );

        var chunks = chunker.chunkParsedDocument(document, DocumentType.QA);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text())
            .contains("Q: 支持什么文件？")
            .contains("A: 支持 PDF 和 Word。");
        assertThat(chunks.get(0).metadata())
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "q-1,a-1");
    }

    @Test
    void exposesStructuralChunksBeforeStandaloneSemanticMerge() {
        var builder = SmartChunkerConfig.builder()
            .targetTokens(120)
            .maxTokens(120)
            .overlapTokens(8)
            .semanticMergeThreshold(0.2d);

        var document = new Document(
            "doc-1",
            "Guide",
            "Retrieval overview.\n\nRetrieval details.",
            Map.of()
        );

        var semanticChunker = new SmartChunker(builder.semanticMergeEnabled(true).build());
        var structuralChunks = semanticChunker.chunkStructural(document);

        var baselineChunker = new SmartChunker(builder.semanticMergeEnabled(false).build());
        var baselineChunks = baselineChunker.chunk(document);

        assertThat(structuralChunks).containsExactlyElementsOf(baselineChunks);
    }

    @Test
    void advancesWhenFirstSentenceAloneFillsChunk() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(3)
            .build());

        var document = new Document(
            "doc-2",
            "Guide",
            "1234567. tail.",
            Map.of()
        );

        assertThat(chunker.chunk(document)).isNotEmpty();
    }

    @Test
    void fallsBackWhenSentenceExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_sentence",
            "Guide",
            "1234567890. Follow-up short sentence.",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenListItemExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_list",
            "Guide",
            "List:\n- 1234567890123456\n- Short",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenListLeadExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_list_lead",
            "Guide",
            "1234567890123456\n- Short",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenListLeadAndItemTogetherExceedMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(8)
            .maxTokens(8)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_list_combo",
            "Guide",
            "Lead\n- 1234",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(8));
    }

    @Test
    void fallsBackWhenTableRowExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(16)
            .maxTokens(16)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_table",
            "Guide",
            "# Data\n| Name | Value |\n| --- | --- |\n| 12345678901234 | Extra |",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(16));
    }

    @Test
    void fallsBackWhenTwoLineTableExceedsMaxTokens() {
        var chunker = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(16)
            .maxTokens(16)
            .overlapTokens(0)
            .build());

        var document = new Document(
            "doc-long_table_header",
            "Guide",
            "| 123456789 | 123456789 |\n| --------- | --------- |",
            Map.of()
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
            assertThat(chunk.text().codePointCount(0, chunk.text().length())).isLessThanOrEqualTo(16));
    }
}
