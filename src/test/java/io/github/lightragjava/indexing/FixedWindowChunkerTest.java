package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowChunkerTest {
    @Test
    void splitsLongContentIntoOrderedChunks() {
        var chunker = new FixedWindowChunker(4, 1);
        var document = new Document("doc-1", "Title", "abcdefghij", Map.of("source", "unit-test"));

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::id)
            .containsExactly("doc-1:0", "doc-1:1", "doc-1:2");
        assertThat(chunks)
            .extracting(Chunk::documentId)
            .containsExactly("doc-1", "doc-1", "doc-1");
        assertThat(chunks)
            .extracting(Chunk::order)
            .containsExactly(0, 1, 2);
        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("abcd", "defg", "ghij");
    }

    @Test
    void keepsConfiguredOverlap() {
        var chunker = new FixedWindowChunker(5, 2);
        var document = new Document("doc-1", "Title", "abcdefghijk", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("abcde", "defgh", "ghijk");
        assertThat(chunks.get(0).text().substring(3)).isEqualTo(chunks.get(1).text().substring(0, 2));
        assertThat(chunks.get(1).text().substring(3)).isEqualTo(chunks.get(2).text().substring(0, 2));
    }
}
