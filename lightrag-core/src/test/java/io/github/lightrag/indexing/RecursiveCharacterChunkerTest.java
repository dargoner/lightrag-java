package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecursiveCharacterChunkerTest {
    @Test
    void recursivelyPrefersStrongSeparatorsBeforeTokenFallback() {
        var chunker = new RecursiveCharacterChunker(
            12,
            2,
            UnicodeCodePointChunkTextTokenizer.INSTANCE,
            List.of("\n\n", "\n", " ", "")
        );
        var document = new Document(
            "doc-r",
            "Guide",
            "alpha beta gamma\n\nsecond paragraph delta epsilon\n\nthird",
            Map.of("source", "unit-test")
        );

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::id)
            .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> "doc-r:" + index)
                .toList());
        assertThat(chunks)
            .extracting(Chunk::order)
            .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size())
                .boxed()
                .toList());
        assertThat(chunks)
            .allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(12));
        assertThat(chunks.get(0).text()).contains("alpha");
    }

    @Test
    void fallsBackToTokenWindowWhenNoSeparatorCanSplit() {
        var chunker = new RecursiveCharacterChunker(
            4,
            1,
            UnicodeCodePointChunkTextTokenizer.INSTANCE,
            List.of("\n", "")
        );
        var document = new Document("doc-r-tight", "Guide", "abcdefghij", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("abcd", "defg", "ghij");
    }

    @Test
    void returnsRecursiveModeForParsedDocuments() {
        var chunker = new RecursiveCharacterChunker(4, 1);
        var parsed = new ParsedDocument(
            "doc-r-mode",
            "Guide",
            "abcdefghij",
            List.of(),
            Map.of()
        );

        var result = chunker.chunk(parsed);

        assertThat(result.effectiveMode()).isEqualTo(ChunkingMode.RECURSIVE);
        assertThat(result.chunks()).hasSize(3);
    }
}
