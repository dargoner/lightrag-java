package io.github.lightrag.indexing;

import io.github.lightrag.model.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticVectorChunkerTest {
    @Test
    void fallsBackToRecursiveWithoutEmbeddingModel() {
        var chunker = new SemanticVectorChunker(6);
        var parsed = new ParsedDocument(
            "doc-v-fallback",
            "Guide",
            "abcdefghij",
            List.of(),
            Map.of("source", "unit-test")
        );

        var result = chunker.chunk(parsed);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.RECURSIVE);
        assertThat(result.fallbackReason()).contains("upstream V fallback to R applied");
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("abcdef", "ghij");
    }

    @Test
    void splitsAtSemanticEmbeddingBreakpoint() {
        var chunker = new SemanticVectorChunker(
            200,
            topicEmbeddingModel(),
            16,
            "percentile",
            50.0d,
            0,
            SemanticVectorChunker.DEFAULT_SENTENCE_SPLIT_REGEX,
            UnicodeCodePointChunkTextTokenizer.INSTANCE
        );
        var parsed = new ParsedDocument(
            "doc-v",
            "Guide",
            "Cats sleep. Kittens play. Revenue rises. Profit grows.",
            List.of(),
            Map.of("source", "unit-test")
        );

        var result = chunker.chunk(parsed);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.SEMANTIC_VECTOR);
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("Cats sleep. Kittens play.", "Revenue rises. Profit grows.");
    }

    @Test
    void resplitsOversizedSemanticPiecesWithRecursiveOverlapZero() {
        var chunker = new SemanticVectorChunker(
            4,
            texts -> texts.stream().map(ignored -> List.of(1.0d, 0.0d)).toList(),
            16,
            "percentile",
            95.0d,
            0,
            SemanticVectorChunker.DEFAULT_SENTENCE_SPLIT_REGEX,
            UnicodeCodePointChunkTextTokenizer.INSTANCE
        );
        var parsed = new ParsedDocument(
            "doc-v-long",
            "Guide",
            "abcdefghijkl",
            List.of(),
            Map.of("source", "unit-test")
        );

        var result = chunker.chunk(parsed);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.SEMANTIC_VECTOR);
        assertThat(result.chunks())
            .extracting(io.github.lightrag.types.Chunk::text)
            .containsExactly("abcd", "efgh", "ijkl");
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(4));
    }

    private static EmbeddingModel topicEmbeddingModel() {
        return texts -> texts.stream()
            .map(text -> text.contains("Revenue") || text.contains("Profit")
                ? List.of(0.0d, 1.0d)
                : List.of(1.0d, 0.0d))
            .toList();
    }
}
