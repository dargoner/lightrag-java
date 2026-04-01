package dev.io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkGranularity;
import io.github.lightrag.api.DocumentIngestOptions;
import io.github.lightrag.indexing.ChunkingOrchestrator;
import io.github.lightrag.indexing.ChunkingStrategyOverride;
import io.github.lightrag.indexing.DocumentParsingOrchestrator;
import io.github.lightrag.indexing.DocumentTypeHint;
import io.github.lightrag.indexing.MineruApiClient;
import io.github.lightrag.indexing.MineruDocumentAdapter;
import io.github.lightrag.indexing.MineruParsingProvider;
import io.github.lightrag.indexing.ParentChildProfile;
import io.github.lightrag.indexing.ParsedDocument;
import io.github.lightrag.indexing.PlainTextParsingProvider;
import io.github.lightrag.indexing.RegexChunkerConfig;
import io.github.lightrag.indexing.SmartChunker;
import io.github.lightrag.indexing.SmartChunkerConfig;
import io.github.lightrag.types.RawDocumentSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PublicDocumentSmartChunkerRealApiManualTest {
    private static final String SECTION_PATH_KEY = "smart_chunker.section_path";
    private static final String CONTENT_TYPE_KEY = "smart_chunker.content_type";
    private static final String LAW_URL = "https://www.constituteproject.org/constitution/United_States_of_America_1992.pdf";
    private static final String QA_URL = "https://oklahoma.gov/content/dam/ok/en/osde/documents/services/teacher-year/tle-quantitative-components/FAQ-VAM-Spring-2016.pdf";
    private static final String BOOK_URL = "https://www.gutenberg.org/files/31632/31632-pdf.pdf";
    private static final String LAW_ZH_URL = "https://www.safe.gov.cn/anhui/file/file/20200818/4a3702f6073e4c218a649c443078de15.pdf";
    private static final String QA_ZH_URL = "https://ynb.nea.gov.cn/xxgk/zcjd/202505/P020260226642837724419.pdf";
    private static final String GENERIC_DENSE_URL = BOOK_URL;
    private static final String GENERIC_ZH_URL = "https://www.cac.gov.cn/files/pdf/baipishu/shuzijingjifazhan.pdf";
    private static final String DOCX_TOC_URL = "https://rst.fujian.gov.cn/zw/zfxxgk/zfxxgkml/zyywgz/zynljs/202601/P020260115320246506269.docx";
    private static final String PDF_FIGURE_URL = "https://sheitc.sh.gov.cn/cmsres/2d/2d6caa1ad8fe4cd88c3f0c3925af7a38/45b2295e486321349c540c4252c2b825.pdf";
    private static final String DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @Test
    void smartChunkerPreservesLawSectionsForPublicHtml() {
        var result = chunkPublicUrl("constitution.pdf", "application/pdf", LAW_URL, DocumentTypeHint.LAW);

        assertThat(result.parsedBlockCount).isPositive();
        assertThat(result.chunkCount).isPositive();
        assertThat(result.sectionedChunkCount).isPositive();
        assertThat(result.anyChunkMatches(text -> {
            var normalized = text.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("article") || normalized.contains("amendment");
        })).isTrue();

        System.out.println("[LAW] parsed_blocks=" + result.parsedBlockCount);
        System.out.println("[LAW] smart_chunks=" + result.chunkCount);
        result.printPreview("[LAW]");
    }

    @Test
    void smartChunkerCombinesQaPairsForPublicHtml() {
        var result = chunkPublicUrl("faq.pdf", "application/pdf", QA_URL, DocumentTypeHint.QA);

        System.out.println("[QA] parsed_blocks=" + result.parsedBlockCount);
        System.out.println("[QA] smart_chunks=" + result.chunkCount);
        result.printPreview("[QA]");

        assertThat(result.parsedBlockCount).isPositive();
        assertThat(result.chunkCount).isPositive();
        assertThat(result.sectionedChunkCount).isPositive();
        assertThat(result.anyChunkMatches(PublicDocumentSmartChunkerRealApiManualTest::containsQuestionMarker)).isTrue();
        assertThat(result.anyChunkMatches(PublicDocumentSmartChunkerRealApiManualTest::containsAnswerMarker)).isTrue();
        assertThat(result.anyChunkMatches(text ->
            containsQuestionMarker(text) && containsAnswerMarker(text)
        )).isTrue();
    }

    @Test
    void smartChunkerPreservesBookSectionsForPublicPdf() {
        var result = chunkPublicUrl("book.pdf", "application/pdf", BOOK_URL, DocumentTypeHint.BOOK);

        System.out.println("[BOOK] parsed_blocks=" + result.parsedBlockCount);
        System.out.println("[BOOK] smart_chunks=" + result.chunkCount);
        result.printPreview("[BOOK]");

        assertThat(result.parsedBlockCount).isPositive();
        assertThat(result.chunkCount).isPositive();
        assertThat(result.sectionedChunkCount).isPositive();
        assertThat(result.anyChunkMatches(text -> {
            var normalized = text.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("project gutenberg")
                || normalized.contains("chapter")
                || normalized.contains("contents");
        })).isTrue();
    }

    @Test
    void smartChunkerPreservesChineseLawSectionsForPublicPdf() {
        var result = chunkPublicUrl("law-zh.pdf", "application/pdf", LAW_ZH_URL, DocumentTypeHint.LAW);

        System.out.println("[LAW_ZH] parsed_blocks=" + result.parsedBlockCount);
        System.out.println("[LAW_ZH] smart_chunks=" + result.chunkCount);
        result.printPreview("[LAW_ZH]");

        assertThat(result.parsedBlockCount).isPositive();
        assertThat(result.chunkCount).isPositive();
        assertThat(result.sectionedChunkCount).isPositive();
        assertThat(result.anyChunkMatches(text ->
            text.contains("民法典") || text.contains("第一条") || text.contains("第一编") || text.contains("总则")
        )).isTrue();
    }

    @Test
    void smartChunkerCombinesChineseQaPairsForPublicPdf() {
        var result = chunkPublicUrl("faq-zh.pdf", "application/pdf", QA_ZH_URL, DocumentTypeHint.QA);

        System.out.println("[QA_ZH] parsed_blocks=" + result.parsedBlockCount);
        System.out.println("[QA_ZH] smart_chunks=" + result.chunkCount);
        result.printPreview("[QA_ZH]");

        assertThat(result.parsedBlockCount).isPositive();
        assertThat(result.chunkCount).isPositive();
        assertThat(result.sectionedChunkCount).isPositive();
        assertThat(result.anyChunkMatches(text -> text.contains("问：") || text.contains("问:"))).isTrue();
        assertThat(result.anyChunkMatches(text -> text.contains("答：") || text.contains("答:"))).isTrue();
        assertThat(result.anyChunkMatches(text ->
            (text.contains("问：") || text.contains("问:"))
                && (text.contains("答：") || text.contains("答:"))
        )).isTrue();
    }

    @Test
    void smartChunkerUsesSemanticBoundariesForGenericDensePublicPdf() {
        var parsed = parsePublicUrl("generic-dense.pdf", "application/pdf", GENERIC_DENSE_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var config = SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build();
        var baselineChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(config.targetTokens())
            .maxTokens(config.maxTokens())
            .overlapTokens(config.overlapTokens())
            .semanticMergeEnabled(false)
            .semanticMergeThreshold(config.semanticMergeThreshold())
            .build())
            .chunk(source);
        var semanticChunks = new SmartChunker(config).chunk(source);

        System.out.println("[GENERIC] parsed_blocks=" + parsed.blocks().size());
        System.out.println("[GENERIC] baseline_chunks=" + baselineChunks.size());
        System.out.println("[GENERIC] semantic_chunks=" + semanticChunks.size());
        semanticChunks.stream()
            .limit(5)
            .forEach(chunk -> System.out.println(
                "[GENERIC] chunk order=" + chunk.order()
                    + " section=" + chunk.metadata().getOrDefault("smart_chunker.section_path", "")
                    + " type=" + chunk.metadata().getOrDefault("smart_chunker.content_type", "")
                    + " text=" + ChunkResult.preview(chunk.text())
            ));

        assertThat(parsed.blocks().size()).isPositive();
        assertThat(baselineChunks.size()).isGreaterThan(semanticChunks.size());
        assertThat(semanticChunks.size()).isGreaterThan(1);
        assertThat(semanticChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .count()).isGreaterThan((long) (semanticChunks.size() * 0.8d));
        assertThat(semanticChunks.stream()
            .map(chunk -> chunk.metadata().get("smart_chunker.section_path"))
            .distinct()
            .count()).isEqualTo(1);
    }

    @Test
    void printsRepresentativeGenericSemanticChunksForInspection() {
        var parsed = parsePublicUrl("generic-dense.pdf", "application/pdf", GENERIC_DENSE_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var semanticChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);

        var tokenCounts = semanticChunks.stream()
            .mapToInt(io.github.lightrag.types.Chunk::tokenCount)
            .summaryStatistics();
        var smallChunkCount = semanticChunks.stream()
            .filter(chunk -> chunk.tokenCount() <= 80)
            .count();

        System.out.println("[GENERIC_PREVIEW] chunk_count=" + semanticChunks.size());
        System.out.println("[GENERIC_PREVIEW] token_min=" + tokenCounts.getMin());
        System.out.println("[GENERIC_PREVIEW] token_max=" + tokenCounts.getMax());
        System.out.println("[GENERIC_PREVIEW] token_avg=" + String.format(java.util.Locale.ROOT, "%.2f", tokenCounts.getAverage()));
        System.out.println("[GENERIC_PREVIEW] token_small_le_80=" + smallChunkCount);
        semanticChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.tokenCount() >= 150)
            .limit(8)
            .forEach(chunk -> System.out.println(
                "[GENERIC_PREVIEW] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + " text=" + ChunkResult.preview(chunk.text())
            ));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(semanticChunks).isNotEmpty();
    }

    @Test
    void printsFullGenericSemanticChunksForInspection() {
        var parsed = parsePublicUrl("generic-dense.pdf", "application/pdf", GENERIC_DENSE_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var semanticChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);

        System.out.println("[GENERIC_FULL] full_text_samples_begin");
        semanticChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.order() >= 13)
            .filter(chunk -> chunk.tokenCount() >= 150)
            .limit(5)
            .forEach(chunk -> System.out.println(
                "[GENERIC_FULL] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));
        System.out.println("[GENERIC_FULL] full_text_samples_end");

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(semanticChunks).isNotEmpty();
    }

    @Test
    void comparesGenericSemanticChunksBetweenMediumAndCoarse() {
        var parsed = parsePublicUrl("generic-dense.pdf", "application/pdf", GENERIC_DENSE_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var mediumChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);
        var coarseChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(480)
            .maxTokens(640)
            .overlapTokens(80)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);

        var mediumStats = mediumChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();
        var coarseStats = coarseChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();

        System.out.println("[GENERIC_COMPARE] medium_count=" + mediumChunks.size());
        System.out.println("[GENERIC_COMPARE] medium_avg=" + String.format(java.util.Locale.ROOT, "%.2f", mediumStats.getAverage()));
        System.out.println("[GENERIC_COMPARE] medium_max=" + mediumStats.getMax());
        mediumChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.order() >= 13)
            .filter(chunk -> chunk.tokenCount() >= 150)
            .limit(3)
            .forEach(chunk -> System.out.println(
                "[GENERIC_COMPARE][MEDIUM] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));

        System.out.println("[GENERIC_COMPARE] coarse_count=" + coarseChunks.size());
        System.out.println("[GENERIC_COMPARE] coarse_avg=" + String.format(java.util.Locale.ROOT, "%.2f", coarseStats.getAverage()));
        System.out.println("[GENERIC_COMPARE] coarse_max=" + coarseStats.getMax());
        coarseChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.order() >= 6)
            .filter(chunk -> chunk.tokenCount() >= 250)
            .limit(3)
            .forEach(chunk -> System.out.println(
                "[GENERIC_COMPARE][COARSE] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(coarseChunks.size()).isLessThan(mediumChunks.size());
    }

    @Test
    void printsFullChineseGenericSemanticChunksForInspection() {
        var parsed = parsePublicUrl("generic-zh.pdf", "application/pdf", GENERIC_ZH_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var semanticChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);

        var tokenCounts = semanticChunks.stream()
            .mapToInt(io.github.lightrag.types.Chunk::tokenCount)
            .summaryStatistics();

        System.out.println("[GENERIC_ZH] chunk_count=" + semanticChunks.size());
        System.out.println("[GENERIC_ZH] token_min=" + tokenCounts.getMin());
        System.out.println("[GENERIC_ZH] token_max=" + tokenCounts.getMax());
        System.out.println("[GENERIC_ZH] token_avg=" + String.format(java.util.Locale.ROOT, "%.2f", tokenCounts.getAverage()));
        System.out.println("[GENERIC_ZH] full_text_samples_begin");
        semanticChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.tokenCount() >= 120)
            .limit(6)
            .forEach(chunk -> System.out.println(
                "[GENERIC_ZH] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));
        System.out.println("[GENERIC_ZH] full_text_samples_end");

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(semanticChunks).isNotEmpty();
    }

    @Test
    void comparesChineseGenericSemanticChunksBetweenMediumAndCoarse() {
        var parsed = parsePublicUrl("generic-zh.pdf", "application/pdf", GENERIC_ZH_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var mediumChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(220)
            .maxTokens(320)
            .overlapTokens(40)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);
        var coarseChunks = new SmartChunker(SmartChunkerConfig.builder()
            .targetTokens(480)
            .maxTokens(640)
            .overlapTokens(80)
            .semanticMergeEnabled(true)
            .semanticMergeThreshold(0.12d)
            .build())
            .chunk(source);

        var mediumStats = mediumChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();
        var coarseStats = coarseChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();

        System.out.println("[GENERIC_ZH_COMPARE] medium_count=" + mediumChunks.size());
        System.out.println("[GENERIC_ZH_COMPARE] medium_avg=" + String.format(java.util.Locale.ROOT, "%.2f", mediumStats.getAverage()));
        System.out.println("[GENERIC_ZH_COMPARE] medium_max=" + mediumStats.getMax());
        mediumChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.tokenCount() >= 140)
            .limit(3)
            .forEach(chunk -> System.out.println(
                "[GENERIC_ZH_COMPARE][MEDIUM] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));

        System.out.println("[GENERIC_ZH_COMPARE] coarse_count=" + coarseChunks.size());
        System.out.println("[GENERIC_ZH_COMPARE] coarse_avg=" + String.format(java.util.Locale.ROOT, "%.2f", coarseStats.getAverage()));
        System.out.println("[GENERIC_ZH_COMPARE] coarse_max=" + coarseStats.getMax());
        coarseChunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().get("smart_chunker.content_type")))
            .filter(chunk -> chunk.tokenCount() >= 260)
            .limit(3)
            .forEach(chunk -> System.out.println(
                "[GENERIC_ZH_COMPARE][COARSE] order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + "\n" + chunk.text() + "\n"
            ));

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(coarseChunks.size()).isLessThan(mediumChunks.size());
    }

    @Test
    void comparesChineseGenericLegacyPlainTextPathAgainstWeakStructuredPath() {
        var parsed = parsePublicUrl("generic-zh.pdf", "application/pdf", GENERIC_ZH_URL, DocumentTypeHint.GENERIC);
        var source = new io.github.lightrag.types.Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
        var legacyChunker = new SmartChunker(new io.github.lightrag.indexing.ChunkingProfile(
            io.github.lightrag.indexing.DocumentType.GENERIC,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            RegexChunkerConfig.empty()
        ).smartChunkerConfig());
        var legacyChunks = legacyChunker.chunk(source);
        var improvedResult = new ChunkingOrchestrator().chunk(parsed, ingestOptions(DocumentTypeHint.GENERIC));
        var improvedChunks = improvedResult.chunks();

        var legacyStats = legacyChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();
        var improvedStats = improvedChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();

        System.out.println("[GENERIC_ZH_PATH_COMPARE] legacy_count=" + legacyChunks.size());
        System.out.println("[GENERIC_ZH_PATH_COMPARE] legacy_avg=" + String.format(java.util.Locale.ROOT, "%.2f", legacyStats.getAverage()));
        System.out.println("[GENERIC_ZH_PATH_COMPARE] legacy_max=" + legacyStats.getMax());
        System.out.println("[GENERIC_ZH_PATH_COMPARE] legacy_noise=" + countNoiseChunks(legacyChunks));
        printRepresentativeChunks("[GENERIC_ZH_PATH_COMPARE][LEGACY]", legacyChunks, 3);

        System.out.println("[GENERIC_ZH_PATH_COMPARE] improved_count=" + improvedChunks.size());
        System.out.println("[GENERIC_ZH_PATH_COMPARE] improved_avg=" + String.format(java.util.Locale.ROOT, "%.2f", improvedStats.getAverage()));
        System.out.println("[GENERIC_ZH_PATH_COMPARE] improved_max=" + improvedStats.getMax());
        System.out.println("[GENERIC_ZH_PATH_COMPARE] improved_noise=" + countNoiseChunks(improvedChunks));
        System.out.println("[GENERIC_ZH_PATH_COMPARE] improved_sections=" + improvedChunks.stream()
            .map(chunk -> chunk.metadata().get(SECTION_PATH_KEY))
            .filter(section -> section != null && !section.isBlank())
            .distinct()
            .count());
        printRepresentativeChunks("[GENERIC_ZH_PATH_COMPARE][IMPROVED]", improvedChunks, 3);

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(legacyChunks).isNotEmpty();
        assertThat(improvedChunks).isNotEmpty();
    }

    @Test
    void comparesChineseGenericAdaptiveChunksAgainstStaticSizing() {
        var parsed = parsePublicUrl("generic-zh.pdf", "application/pdf", GENERIC_ZH_URL, DocumentTypeHint.GENERIC);
        var staticChunks = chunkParsedGeneric(parsed, SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .adaptiveChunkingEnabled(false)
            .semanticMergeEnabled(true)
            .build());
        var adaptiveChunks = chunkParsedGeneric(parsed, SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .build());

        var staticStats = staticChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();
        var adaptiveStats = adaptiveChunks.stream().mapToInt(io.github.lightrag.types.Chunk::tokenCount).summaryStatistics();

        System.out.println("[GENERIC_ZH_ADAPTIVE] static_count=" + staticChunks.size());
        System.out.println("[GENERIC_ZH_ADAPTIVE] static_avg=" + String.format(java.util.Locale.ROOT, "%.2f", staticStats.getAverage()));
        System.out.println("[GENERIC_ZH_ADAPTIVE] static_max=" + staticStats.getMax());
        printRepresentativeChunks("[GENERIC_ZH_ADAPTIVE][STATIC]", staticChunks, 3);

        System.out.println("[GENERIC_ZH_ADAPTIVE] adaptive_count=" + adaptiveChunks.size());
        System.out.println("[GENERIC_ZH_ADAPTIVE] adaptive_avg=" + String.format(java.util.Locale.ROOT, "%.2f", adaptiveStats.getAverage()));
        System.out.println("[GENERIC_ZH_ADAPTIVE] adaptive_max=" + adaptiveStats.getMax());
        printRepresentativeChunks("[GENERIC_ZH_ADAPTIVE][ADAPTIVE]", adaptiveChunks, 3);

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(staticChunks).isNotEmpty();
        assertThat(adaptiveChunks).isNotEmpty();
    }

    @Test
    void inspectsPublicDocxTocBoundarySample() {
        var parsed = parsePublicUrl("toc.docx", DOCX_MEDIA_TYPE, DOCX_TOC_URL, DocumentTypeHint.GENERIC);
        var chunks = chunkParsedGeneric(parsed, SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .build());

        System.out.println("[DOCX_TOC] parsed_blocks=" + parsed.blocks().size());
        System.out.println("[DOCX_TOC] chunk_count=" + chunks.size());
        printMatchingParsedBlocks("[DOCX_TOC][PARSED]", parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeTocBoundaryText, 5);
        printMatchingChunks("[DOCX_TOC][MATCH]", chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeTocBoundaryText, 5);

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(anyParsedBlockMatches(parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeTocBoundaryText)
            || anyChunkMatches(chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeTocBoundaryText)).isTrue();
    }

    @Test
    void inspectsPublicPdfFigureCaptionBoundarySample() {
        var parsed = parsePublicUrl("5g-smart-medical.pdf", "application/pdf", PDF_FIGURE_URL, DocumentTypeHint.GENERIC);
        var chunks = chunkParsedGeneric(parsed, SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .build());

        System.out.println("[PDF_FIGURE] parsed_blocks=" + parsed.blocks().size());
        System.out.println("[PDF_FIGURE] chunk_count=" + chunks.size());
        printMatchingParsedBlocks("[PDF_FIGURE][PARSED]", parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeFigureCaptionBoundaryText, 5);
        printMatchingChunks("[PDF_FIGURE][MATCH]", chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeFigureCaptionBoundaryText, 5);
        printChunksContainingMarkers("[PDF_FIGURE][CONTAINS]", chunks, List.of("图 1-4", "图 3-2", "图 4-1"), 5);

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(anyParsedBlockMatches(parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeFigureCaptionBoundaryText)
            || anyChunkMatches(chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeFigureCaptionBoundaryText)).isTrue();
    }

    @Test
    void inspectsChinesePdfOcrLineBreakBoundarySample() {
        var parsed = parsePublicUrl("generic-zh.pdf", "application/pdf", GENERIC_ZH_URL, DocumentTypeHint.GENERIC);
        var chunks = chunkParsedGeneric(parsed, SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .adaptiveChunkingEnabled(true)
            .semanticMergeEnabled(true)
            .build());

        System.out.println("[PDF_OCR_BREAK] parsed_blocks=" + parsed.blocks().size());
        System.out.println("[PDF_OCR_BREAK] chunk_count=" + chunks.size());
        printMatchingParsedBlocks("[PDF_OCR_BREAK][PARSED]", parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeOcrBrokenBoundaryText, 5);
        printMatchingChunks("[PDF_OCR_BREAK][MATCH]", chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeOcrBrokenBoundaryText, 5);

        assertThat(parsed.blocks()).isNotEmpty();
        assertThat(anyParsedBlockMatches(parsed, PublicDocumentSmartChunkerRealApiManualTest::looksLikeOcrBrokenBoundaryText)
            || anyChunkMatches(chunks, PublicDocumentSmartChunkerRealApiManualTest::looksLikeOcrBrokenBoundaryText)).isTrue();
    }

    private static boolean containsQuestionMarker(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("q:")
            || normalized.contains("q.")
            || normalized.contains("question:")
            || normalized.contains("问：")
            || normalized.contains("问:");
    }

    private static boolean containsAnswerMarker(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("a:")
            || normalized.contains("a.")
            || normalized.contains("answer:")
            || normalized.contains("答：")
            || normalized.contains("答:");
    }

    private static ChunkResult chunkPublicUrl(
        String fileName,
        String mediaType,
        String sourceUrl,
        DocumentTypeHint documentTypeHint
    ) {
        var options = ingestOptions(documentTypeHint);
        var parsed = parsePublicUrl(fileName, mediaType, sourceUrl, documentTypeHint);
        var chunkingResult = new ChunkingOrchestrator().chunk(parsed, options);

        assertThat(parsed.metadata())
            .containsEntry("parse_mode", "mineru")
            .containsEntry("parse_backend", "mineru_api");
        assertThat(chunkingResult.effectiveMode().name()).isEqualTo("SMART");

        var sectionedCount = chunkingResult.chunks().stream()
            .map(chunk -> chunk.metadata().get("smart_chunker.section_path"))
            .filter(path -> path != null && !path.isBlank())
            .count();
        return new ChunkResult(parsed.blocks().size(), chunkingResult.chunks().size(), sectionedCount, chunkingResult);
    }

    private static ParsedDocument parsePublicUrl(
        String fileName,
        String mediaType,
        String sourceUrl,
        DocumentTypeHint documentTypeHint
    ) {
        var parsingOrchestrator = new DocumentParsingOrchestrator(
            new PlainTextParsingProvider(),
            new MineruParsingProvider(
                new MineruApiClient(new MineruApiClient.HttpTransport(
                    "https://mineru.net/api/v4/extract/task",
                    mineruApiKey(),
                    Duration.ofSeconds(90),
                    1_000,
                    90
                )),
                new MineruDocumentAdapter()
            ),
            null
        );
        return parsingOrchestrator.parse(
            RawDocumentSource.bytes(
                fileName,
                new byte[] {1},
                mediaType,
                Map.of(MineruApiClient.SOURCE_URL_METADATA_KEY, sourceUrl)
            ),
            ingestOptions(documentTypeHint)
        );
    }

    private static DocumentIngestOptions ingestOptions(DocumentTypeHint documentTypeHint) {
        return new DocumentIngestOptions(
            documentTypeHint,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.SMART,
            RegexChunkerConfig.empty(),
            ParentChildProfile.disabled()
        );
    }

    private static String mineruApiKey() {
        var apiKey = System.getenv("MINERU_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "MINERU_API_KEY is required for this manual test");
        return apiKey;
    }

    @SuppressWarnings("unchecked")
    private static List<io.github.lightrag.types.Chunk> chunkParsedGeneric(ParsedDocument parsed, SmartChunkerConfig config) {
        try {
            var method = SmartChunker.class.getDeclaredMethod(
                "chunkParsedDocument",
                ParsedDocument.class,
                Class.forName("io.github.lightrag.indexing.DocumentType")
            );
            method.setAccessible(true);
            var documentType = Enum.valueOf(
                (Class<Enum>) Class.forName("io.github.lightrag.indexing.DocumentType"),
                "GENERIC"
            );
            return (List<io.github.lightrag.types.Chunk>) method.invoke(new SmartChunker(config), parsed, documentType);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to invoke SmartChunker parsed-document path for manual test", exception);
        }
    }

    private static long countNoiseChunks(List<io.github.lightrag.types.Chunk> chunks) {
        return chunks.stream()
            .map(io.github.lightrag.types.Chunk::text)
            .map(String::strip)
            .filter(text -> text.equals("目 录")
                || text.equals("text_list text")
                || text.matches("[-—–]?\\s*\\d+\\s*[-—–]?")
                || text.matches("第\\s*\\d+\\s*页"))
            .count();
    }

    private static boolean anyParsedBlockMatches(ParsedDocument parsed, java.util.function.Predicate<String> predicate) {
        return parsed.blocks().stream()
            .map(io.github.lightrag.indexing.ParsedBlock::text)
            .anyMatch(predicate);
    }

    private static boolean anyChunkMatches(List<io.github.lightrag.types.Chunk> chunks, java.util.function.Predicate<String> predicate) {
        return chunks.stream()
            .map(io.github.lightrag.types.Chunk::text)
            .anyMatch(predicate);
    }

    private static void printMatchingChunks(
        String prefix,
        List<io.github.lightrag.types.Chunk> chunks,
        java.util.function.Predicate<String> predicate,
        int limit
    ) {
        chunks.stream()
            .filter(chunk -> predicate.test(chunk.text()))
            .limit(limit)
            .forEach(chunk -> System.out.println(
                prefix + " order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + " section=" + chunk.metadata().getOrDefault(SECTION_PATH_KEY, "")
                    + "\n" + chunk.text() + "\n"
            ));
    }

    private static void printMatchingParsedBlocks(
        String prefix,
        ParsedDocument parsed,
        java.util.function.Predicate<String> predicate,
        int limit
    ) {
        parsed.blocks().stream()
            .filter(block -> predicate.test(block.text()))
            .limit(limit)
            .forEach(block -> System.out.println(
                prefix + " type=" + block.blockType()
                    + " section=" + block.sectionPath()
                    + "\n" + block.text() + "\n"
            ));
    }

    private static void printChunksContainingMarkers(
        String prefix,
        List<io.github.lightrag.types.Chunk> chunks,
        List<String> markers,
        int limit
    ) {
        chunks.stream()
            .filter(chunk -> markers.stream().anyMatch(chunk.text()::contains))
            .limit(limit)
            .forEach(chunk -> System.out.println(
                prefix + " order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + " section=" + chunk.metadata().getOrDefault(SECTION_PATH_KEY, "")
                    + "\n" + chunk.text() + "\n"
            ));
    }

    private static boolean looksLikeFigureCaptionBoundaryText(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("^(图|表)\\s*[0-9一二三四五六七八九十].*")
            || normalized.matches("^chart\\s*[0-9].*")
            || normalized.matches("^figure\\s*[0-9].*");
    }

    private static boolean looksLikeTocBoundaryText(String text) {
        var normalized = text.strip().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("table of contents")
            || normalized.equals("contents")
            || normalized.contains("目录")
            || normalized.contains("目录清单")
            || normalized.matches("^article\\s+\\d+.*")
            || normalized.contains("toc \\o");
    }

    private static boolean looksLikeOcrBrokenBoundaryText(String text) {
        var normalized = text.strip();
        return normalized.matches(".*[\\p{IsHan}]\\s+[\\p{IsHan}].*")
            || normalized.contains("机谁能")
            || normalized.contains("发展 础部分");
    }

    private static void printRepresentativeChunks(String prefix, List<io.github.lightrag.types.Chunk> chunks, int limit) {
        chunks.stream()
            .filter(chunk -> "text".equals(chunk.metadata().getOrDefault(CONTENT_TYPE_KEY, "text")))
            .filter(chunk -> chunk.tokenCount() >= 180)
            .limit(limit)
            .forEach(chunk -> System.out.println(
                prefix + " order=" + chunk.order()
                    + " tokens=" + chunk.tokenCount()
                    + " section=" + chunk.metadata().getOrDefault(SECTION_PATH_KEY, "")
                    + "\n" + chunk.text() + "\n"
            ));
    }

    private record ChunkResult(
        int parsedBlockCount,
        int chunkCount,
        long sectionedChunkCount,
        io.github.lightrag.indexing.ChunkingResult chunkingResult
    ) {
        boolean anyChunkContains(String marker) {
            return anyChunkMatches(text -> text.contains(marker));
        }

        boolean anyChunkMatches(java.util.function.Predicate<String> predicate) {
            return chunkingResult.chunks().stream()
                .map(io.github.lightrag.types.Chunk::text)
                .anyMatch(predicate);
        }

        void printPreview(String prefix) {
            chunkingResult.chunks().stream()
                .limit(5)
                .forEach(chunk -> System.out.println(
                    prefix
                        + " chunk order=" + chunk.order()
                        + " section=" + chunk.metadata().getOrDefault("smart_chunker.section_path", "")
                        + " type=" + chunk.metadata().getOrDefault("smart_chunker.content_type", "")
                        + " text=" + preview(chunk.text())
                ));
        }

        private static String preview(String text) {
            var normalized = text.replaceAll("\\s+", " ").strip();
            return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
        }
    }
}
