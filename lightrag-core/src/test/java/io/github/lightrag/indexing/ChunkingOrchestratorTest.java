package io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkGranularity;
import io.github.lightrag.api.DocumentIngestOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkingOrchestratorTest {
    @Test
    void acceptsUpstreamFixedAliasAsFixedStrategy() {
        var options = new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            "F"
        );
        var profile = new ChunkingProfile(
            DocumentType.GENERIC,
            ChunkGranularity.MEDIUM,
            options.strategyOverride(),
            RegexChunkerConfig.empty()
        );

        assertThat(options.strategyOverride()).isEqualTo(ChunkingStrategyOverride.FIXED);
        assertThat(ChunkingOrchestrator.resolveInitialMode(profile)).isEqualTo(ChunkingMode.FIXED);
    }

    @Test
    void acceptsUpstreamParagraphAliasAsParagraphStrategy() {
        var options = new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            "P"
        );
        var profile = new ChunkingProfile(
            DocumentType.GENERIC,
            ChunkGranularity.MEDIUM,
            options.strategyOverride(),
            RegexChunkerConfig.empty()
        );

        assertThat(options.strategyOverride()).isEqualTo(ChunkingStrategyOverride.PARAGRAPH);
        assertThat(ChunkingOrchestrator.resolveInitialMode(profile)).isEqualTo(ChunkingMode.PARAGRAPH);
    }

    @Test
    void paragraphStrategyKeepsSiblingSectionsSeparated() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new ParagraphSemanticChunker(
                SmartChunkerConfig.builder()
                    .targetTokens(40)
                    .maxTokens(80)
                    .overlapTokens(10)
                    .semanticMergeEnabled(false)
                    .build(),
                new RecursiveCharacterChunker(80, 10)
            ),
            new RegexChunker(new FixedWindowChunker(80, 10)),
            new FixedWindowChunker(80, 10)
        );
        var parsed = new ParsedDocument(
            "doc-p",
            "Guide",
            "",
            List.of(
                new ParsedBlock(
                    "a",
                    "paragraph",
                    "alpha intro\nalpha detail",
                    "Chapter > A",
                    List.of("Chapter", "A"),
                    null,
                    "",
                    1,
                    Map.of("sidecar.level", "2")
                ),
                new ParsedBlock(
                    "b",
                    "paragraph",
                    "beta intro\nbeta detail",
                    "Chapter > B",
                    List.of("Chapter", "B"),
                    null,
                    "",
                    2,
                    Map.of("sidecar.level", "2")
                )
            ),
            Map.of("parse_mode", "sidecar")
        );

        var result = orchestrator.chunk(parsed, new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.PARAGRAPH,
            RegexChunkerConfig.empty()
        ));

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.PARAGRAPH);
        assertThat(result.chunks()).hasSize(2);
        assertThat(result.chunks().get(0).text()).contains("alpha").doesNotContain("beta");
        assertThat(result.chunks().get(1).text()).contains("beta").doesNotContain("alpha");
        assertThat(result.chunks().get(0).metadata())
            .containsEntry(ParagraphSemanticChunker.METADATA_HEADING, "A")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "a");
    }

    @Test
    void paragraphStrategySplitsOversizedBlocksAndAddsPartSuffixes() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new ParagraphSemanticChunker(
                SmartChunkerConfig.builder()
                    .targetTokens(30)
                    .maxTokens(45)
                    .overlapTokens(5)
                    .semanticMergeEnabled(false)
                    .build(),
                new RecursiveCharacterChunker(45, 5)
            ),
            new RegexChunker(new FixedWindowChunker(45, 5)),
            new FixedWindowChunker(45, 5)
        );
        var parsed = new ParsedDocument(
            "doc-long",
            "Guide",
            "",
            List.of(new ParsedBlock(
                "long",
                "paragraph",
                "lead\n" + "a".repeat(70) + "\nanchor\n" + "b".repeat(70),
                "Chapter > Long",
                List.of("Chapter", "Long"),
                null,
                "",
                1,
                Map.of("sidecar.level", "2")
            )),
            Map.of("parse_mode", "sidecar")
        );

        var result = orchestrator.chunk(parsed, new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.PARAGRAPH,
            RegexChunkerConfig.empty()
        ));

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.PARAGRAPH);
        assertThat(result.chunks()).hasSizeGreaterThan(1);
        assertThat(result.chunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata().get(ParagraphSemanticChunker.METADATA_HEADING))
                .contains("[part "));
    }

    @Test
    void paragraphStrategySplitsJsonTablesByRows() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new ParagraphSemanticChunker(
                SmartChunkerConfig.builder()
                    .targetTokens(40)
                    .maxTokens(80)
                    .overlapTokens(10)
                    .semanticMergeEnabled(false)
                    .build(),
                new RecursiveCharacterChunker(80, 10)
            ),
            new RegexChunker(new FixedWindowChunker(80, 10)),
            new FixedWindowChunker(80, 10)
        );
        var table = "<table format=\"json\">["
            + "{\"name\":\"alpha\",\"desc\":\"" + "a".repeat(30) + "\"},"
            + "{\"name\":\"beta\",\"desc\":\"" + "b".repeat(30) + "\"},"
            + "{\"name\":\"gamma\",\"desc\":\"" + "c".repeat(30) + "\"}"
            + "]</table>";
        var parsed = new ParsedDocument(
            "doc-table",
            "Guide",
            "",
            List.of(new ParsedBlock(
                "table-block",
                "table",
                "intro\n" + table + "\noutro",
                "Chapter > Table",
                List.of("Chapter", "Table"),
                null,
                "",
                1,
                Map.of("sidecar.level", "2")
            )),
            Map.of("parse_mode", "sidecar")
        );

        var result = orchestrator.chunk(parsed, new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.PARAGRAPH,
            RegexChunkerConfig.empty()
        ));

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.PARAGRAPH);
        assertThat(result.chunks()).hasSizeGreaterThan(1);
        assertThat(result.chunks())
            .anySatisfy(chunk -> assertThat(chunk.text()).contains("<table format=\"json\">["));
        assertThat(result.chunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "table-block"));
    }

    @Test
    void paragraphStrategyFallsBackWhenSidecarBlocksAreUnavailable() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new ParagraphSemanticChunker(
                SmartChunkerConfig.builder()
                    .targetTokens(40)
                    .maxTokens(60)
                    .overlapTokens(5)
                    .semanticMergeEnabled(false)
                    .build(),
                new RecursiveCharacterChunker(60, 5)
            ),
            new RegexChunker(new FixedWindowChunker(60, 5)),
            new FixedWindowChunker(60, 5)
        );
        var parsed = new ParsedDocument(
            "plain",
            "plain.txt",
            "plain content without sidecar",
            List.of(),
            Map.of("parse_mode", "plain")
        );

        var result = orchestrator.chunk(parsed, new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.PARAGRAPH,
            RegexChunkerConfig.empty()
        ));

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.RECURSIVE);
        assertThat(result.downgradedToFixed()).isFalse();
        assertThat(result.fallbackReason()).contains("upstream P fallback to R applied");
    }

    @Test
    void acceptsUpstreamRecursiveAliasAsRecursiveStrategy() {
        var options = new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            "R"
        );
        var profile = new ChunkingProfile(
            DocumentType.GENERIC,
            ChunkGranularity.MEDIUM,
            options.strategyOverride(),
            RegexChunkerConfig.empty()
        );

        assertThat(options.strategyOverride()).isEqualTo(ChunkingStrategyOverride.RECURSIVE);
        assertThat(ChunkingOrchestrator.resolveInitialMode(profile)).isEqualTo(ChunkingMode.RECURSIVE);
    }

    @Test
    void rejectsUnsupportedUpstreamVectorAlias() {
        assertThatThrownBy(() -> ChunkingStrategyOverride.fromExternalName("V"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vector breakpoint chunking is not implemented");
    }

    @Test
    void autoStrategySelectsRegexWhenRulesExistEvenWithoutExplicitOverride() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new RegexChunker(new FixedWindowChunker(12, 2)),
            new FixedWindowChunker(12, 2)
        );
        var parsed = new ParsedDocument(
            "doc-1",
            "Guide",
            "Section 1\nalpha beta\nSection 2\ngamma delta",
            List.of(),
            Map.of("source", "unit-test")
        );
        var options = new DocumentIngestOptions(
            DocumentTypeHint.AUTO,
            ChunkGranularity.MEDIUM,
            ChunkingStrategyOverride.AUTO,
            new RegexChunkerConfig(List.of(new RegexChunkRule("(?m)^Section\\s+\\d+")))
        );

        var result = orchestrator.chunk(parsed, options);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.REGEX);
        assertThat(result.downgradedToFixed()).isFalse();
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("Section 1\nalpha beta", "Section 2\ngamma delta");
    }

    @Test
    void genericSmartPathUsesStructuredBlocksWhenAvailable() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.builder()
                .targetTokens(12)
                .maxTokens(18)
                .overlapTokens(2)
                .build()),
            new RegexChunker(new FixedWindowChunker(12, 2)),
            new FixedWindowChunker(12, 2)
        );
        var parsed = new ParsedDocument(
            "doc-1",
            "Guide",
            "Alpha beta gamma delta epsilon zeta eta theta.",
            List.of(
                new ParsedBlock(
                    "title-1",
                    "title",
                    "第二章 数据要素",
                    "第二章 数据要素",
                    List.of("第二章 数据要素"),
                    1,
                    null,
                    1,
                    Map.of("level", "1")
                ),
                new ParsedBlock(
                    "noise-1",
                    "paragraph",
                    "- 12 -",
                    "第二章 数据要素",
                    List.of("第二章 数据要素"),
                    1,
                    null,
                    2,
                    Map.of()
                ),
                new ParsedBlock(
                    "block-1",
                    "paragraph",
                    "结构化正文第一段。",
                    "第二章 数据要素",
                    List.of("第二章 数据要素"),
                    1,
                    null,
                    3,
                    Map.of()
                )
            ),
            Map.of("source", "unit-test")
        );

        var result = orchestrator.chunk(parsed, DocumentIngestOptions.defaults());

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.SMART);
        assertThat(result.downgradedToFixed()).isFalse();
        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).text()).isEqualTo("结构化正文第一段。");
        assertThat(result.chunks().get(0).metadata())
            .containsEntry(SmartChunkMetadata.SECTION_PATH, "第二章 数据要素")
            .containsEntry(SmartChunkMetadata.SOURCE_BLOCK_IDS, "block-1");
    }

    @Test
    void marksTikaPlaintextFallbackChunksWithFallbackDiagnostics() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new RegexChunker(new FixedWindowChunker(12, 2)),
            new FixedWindowChunker(12, 2)
        );
        var parsed = new ParsedDocument(
            "doc-fallback",
            "guide.pdf",
            "第一段正文。\n\n第二段正文。",
            List.of(),
            Map.of(
                "parse_mode", "tika",
                "parse_backend", "tika",
                "parse_error_reason", "mineru offline"
            )
        );

        var result = orchestrator.chunk(parsed, DocumentIngestOptions.defaults());

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.SMART);
        assertThat(result.chunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .containsEntry(SmartChunkMetadata.PARSE_QUALITY, "fallback_plaintext")
                .containsEntry(SmartChunkMetadata.IMAGE_PATH_UNAVAILABLE, "true"));
    }

    @Test
    void doesNotMarkRegularPlainTextChunksAsFallbackPlaintext() {
        var orchestrator = new ChunkingOrchestrator(
            new DocumentTypeResolver(),
            new SmartChunker(SmartChunkerConfig.defaults()),
            new RegexChunker(new FixedWindowChunker(12, 2)),
            new FixedWindowChunker(12, 2)
        );
        var parsed = new ParsedDocument(
            "doc-plain",
            "guide.txt",
            "普通文本正文。",
            List.of(),
            Map.of(
                "parse_mode", "plain",
                "parse_backend", "plain"
            )
        );

        var result = orchestrator.chunk(parsed, DocumentIngestOptions.defaults());

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.SMART);
        assertThat(result.chunks())
            .allSatisfy(chunk -> assertThat(chunk.metadata())
                .doesNotContainKeys(SmartChunkMetadata.PARSE_QUALITY, SmartChunkMetadata.IMAGE_PATH_UNAVAILABLE));
    }
}
