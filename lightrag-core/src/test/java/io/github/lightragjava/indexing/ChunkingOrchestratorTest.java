package io.github.lightragjava.indexing;

import io.github.lightragjava.api.ChunkGranularity;
import io.github.lightragjava.api.DocumentIngestOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkingOrchestratorTest {
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
            .extracting(io.github.lightragjava.types.Chunk::text)
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
