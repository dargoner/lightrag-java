package io.github.lightrag.query;

import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkHeadingsTest {
    private static final TokenCounter TOKEN_COUNTER = new HeuristicTokenCounter();

    @Test
    void joinsTheHeadingPathIntoTheUpstreamBreadcrumb() {
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"第三章\",\"3.2 租房提取\"]"));

        assertThat(ChunkHeadings.resolve(chunk, TOKEN_COUNTER)).contains("第三章 → 3.2 租房提取");
    }

    @Test
    void fallsBackToThePreJoinedSectionPath() {
        var chunk = chunkWithMetadata(Map.of("smart_chunker.section_path", "Chapter 1 > Setup"));

        assertThat(ChunkHeadings.resolve(chunk, TOKEN_COUNTER)).contains("Chapter 1 → Setup");
    }

    @Test
    void capsEachLevelAtEightyCharactersWithAnEllipsis() {
        var level = "x".repeat(200);
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"" + level + "\"]"));

        assertThat(ChunkHeadings.resolve(chunk, TOKEN_COUNTER)).contains("x".repeat(79) + "…");
    }

    @Test
    void collapsesDeepBreadcrumbsThatExceedTheTokenBudget() {
        var level = "中".repeat(79);
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"" + level + "\",\"" + level + "\",\""
            + level + "\",\"" + level + "\"]"));

        assertThat(ChunkHeadings.resolve(chunk, TOKEN_COUNTER)).contains(level + " → … → " + level);
    }

    @Test
    void stripsControlCharactersAndTheBreadcrumbSeparatorFromLevels() {
        var chunk = chunkWithMetadata(Map.of("headingPath", "[\"  a→b\u200d\\u0007\\tc \"]"));

        assertThat(ChunkHeadings.resolve(chunk, TOKEN_COUNTER)).contains("a b c");
    }

    @Test
    void returnsEmptyForChunksWithoutHeadingMetadata() {
        assertThat(ChunkHeadings.resolve(chunkWithMetadata(Map.of()), TOKEN_COUNTER)).isEmpty();
    }

    private static ScoredChunk chunkWithMetadata(Map<String, String> metadata) {
        return new ScoredChunk("c1", new Chunk("c1", "doc-1", "text", 1, 0, metadata), 1.0d);
    }
}
