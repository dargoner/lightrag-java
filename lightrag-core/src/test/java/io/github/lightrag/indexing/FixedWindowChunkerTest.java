package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void doesNotSplitSurrogatePairsAcrossChunkBoundaries() {
        var chunker = new FixedWindowChunker(4, 1);
        var document = new Document("doc-emoji", "Title", "ab😀cd😀ef", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .allSatisfy(text -> assertThat(hasIsolatedSurrogate(text)).isFalse());
        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("ab😀c", "cd😀e", "ef");
    }

    @Test
    void advancesPastSurrogateOverlapWithoutLoopingForever() {
        var chunker = new FixedWindowChunker(1, 0);
        var document = new Document("doc-emoji-tight", "Title", "😀😀", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("😀", "😀");
    }

    @Test
    void countsChunkTokensByUnicodeCodePoint() {
        var chunker = new FixedWindowChunker(4, 1);
        var document = new Document("doc-emoji-count", "Title", "ab😀cd😀ef", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::tokenCount)
            .containsExactly(4, 4, 2);
    }

    @Test
    void usesConfiguredTokenizerForWindowingDecodingAndTokenCount() {
        var chunker = new FixedWindowChunker(3, 1, new PipeSeparatedTokenizer());
        var document = new Document("doc-tokenizer", "Title", "A|B|C|D|E", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("A|B|C", "C|D|E");
        assertThat(chunks)
            .extracting(Chunk::tokenCount)
            .containsExactly(3, 3);
    }

    @Test
    void splitByCharacterOnlyKeepsDelimitedSegmentsWithoutTokenWindowSplitting() {
        var chunker = new FixedWindowChunker(
            8,
            1,
            UnicodeCodePointChunkTextTokenizer.INSTANCE,
            "\n## ",
            true
        );
        var document = new Document("doc-delimited", "Title", "Intro\n## Alpha\n## Beta", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("Intro", "Alpha", "Beta");
    }

    @Test
    void splitByCharacterThenTokenSplitsOversizedSegmentsWhenNotOnly() {
        var chunker = new FixedWindowChunker(
            4,
            1,
            UnicodeCodePointChunkTextTokenizer.INSTANCE,
            "|",
            false
        );
        var document = new Document("doc-delimited-long", "Title", "ab|cdefghi", Map.of());

        var chunks = chunker.chunk(document);

        assertThat(chunks)
            .extracting(Chunk::text)
            .containsExactly("ab", "cdef", "fghi");
    }

    @Test
    void splitByCharacterOnlyRejectsOversizedSegmentsLikeUpstreamFixedChunker() {
        var chunker = new FixedWindowChunker(
            4,
            1,
            UnicodeCodePointChunkTextTokenizer.INSTANCE,
            "|",
            true
        );
        var document = new Document("doc-delimited-too-long", "Title", "ab|cdefghi", Map.of());

        assertThatThrownBy(() -> chunker.chunk(document))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("split_by_character exceeds token limit");
    }

    private static boolean hasIsolatedSurrogate(String text) {
        for (var index = 0; index < text.length(); index++) {
            var current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return true;
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static final class PipeSeparatedTokenizer implements ChunkTextTokenizer {
        @Override
        public List<String> encode(String text) {
            return List.of(text.split("\\|", -1));
        }

        @Override
        public String decode(List<String> tokens) {
            return String.join("|", tokens);
        }

        @Override
        public int count(String text) {
            return encode(text).size();
        }
    }
}
