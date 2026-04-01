package io.github.lightrag.indexing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegexChunkerTest {
    @Test
    void splitsTextByRegexMatchesAndKeepsRegexMode() {
        var chunker = new RegexChunker(new FixedWindowChunker(12, 2));
        var document = new ParsedDocument(
            "doc-1",
            "Guide",
            "Section 1\nalpha beta\nSection 2\ngamma delta",
            List.of(),
            Map.of("source", "unit-test")
        );
        var config = new RegexChunkerConfig(List.of(new RegexChunkRule("(?m)^Section\\s+\\d+")));

        var result = chunker.chunk(document, config);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.REGEX);
        assertThat(result.downgradedToFixed()).isFalse();
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("Section 1\nalpha beta", "Section 2\ngamma delta");
    }

    @Test
    void fallsBackToFixedWindowWhenRulesDoNotMatchAnyBoundary() {
        var chunker = new RegexChunker(new FixedWindowChunker(8, 2));
        var document = new ParsedDocument(
            "doc-1",
            "Guide",
            "abcdefghijklmno",
            List.of(),
            Map.of()
        );
        var config = new RegexChunkerConfig(List.of(new RegexChunkRule("(?m)^Chapter\\s+\\d+")));

        var result = chunker.chunk(document, config);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.FIXED);
        assertThat(result.downgradedToFixed()).isTrue();
        assertThat(result.fallbackReason()).contains("regex");
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("abcdefgh", "ghijklmn", "mno");
    }
}
