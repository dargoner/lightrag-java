package io.github.lightrag.query;

import io.github.lightrag.model.HeuristicTokenCounter;
import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkBudgetTruncatorTest {
    private final TokenCounter counter = new HeuristicTokenCounter();
    private final ChunkBudgetTruncator truncator = new ChunkBudgetTruncator(counter);

    @Test
    void keepsWholeChunksWhileTheyFit() {
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta"));

        var kept = truncate(chunks, counter.countTokens(render(chunks)));

        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1", "c2");
    }

    @Test
    void dropsTheBoundaryChunkWholeInsteadOfTrimmingItsText() {
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta epsilon zeta"));

        var kept = truncate(chunks, counter.countTokens(render(List.of(chunks.get(0)))));

        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
        assertThat(kept).extracting(chunk -> chunk.chunk().text())
            .containsExactly("alpha beta");
    }

    @Test
    void stageTwoShrinksWhatStageOneOverAdmitted() {
        var chunks = List.of(chunkWithText("c1", "alpha beta"), chunkWithText("c2", "gamma delta"));
        var oneRendered = counter.countTokens(render(List.of(chunks.get(0))));

        var kept = truncate(chunks, oneRendered + 2);

        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void cjkTextIsMeasuredThroughTheSameProjectionItRendersWith() {
        var chunks = List.of(chunkWithText("c1", "住房公积金提取流程"), chunkWithText("c2", "租房提取申请材料"));
        var budget = counter.countTokens(render(List.of(chunks.get(0))));

        var kept = truncate(chunks, budget);

        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void escapedJsonProjectionsAreMeasuredByTheRendererNotTheRawText() {
        Function<List<ScoredChunk>, String> jsonRenderer = list -> list.stream()
            .map(chunk -> "{\"content\":\"" + chunk.chunk().text().replace("\"", "\\\"").replace("\n", "\\n") + "\"}")
            .collect(Collectors.joining("\n"));
        var chunks = List.of(
            chunkWithText("c1", "safe"),
            chunkWithText("c2", "\"\"\"\"\"\"\"\" and\nescapes")
        );
        var budget = counter.countTokens(jsonRenderer.apply(List.of(chunks.get(0))));

        var kept = truncator.truncate(chunks, budget, jsonRenderer,
            chunk -> "{\"content\":\"" + chunk.chunk().text() + "\"}");

        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactly("c1");
    }

    @Test
    void renderedReferenceIdsStillFitTheBudget() {
        var chunks = new ArrayList<ScoredChunk>();
        for (int index = 1; index <= 12; index++) {
            chunks.add(chunkWithText("c" + index, "chunk number " + index + " text"));
        }
        Function<List<ScoredChunk>, String> numbered = list -> IntStream.range(0, list.size())
            .mapToObj(index -> "[" + (index + 1) + "] " + list.get(index).chunk().text())
            .collect(Collectors.joining("\n"));
        var budget = counter.countTokens(numbered.apply(chunks.subList(0, 9)));

        var kept = truncator.truncate(chunks, budget, numbered, chunk -> chunk.chunk().text());

        assertThat(counter.countTokens(numbered.apply(kept))).isLessThanOrEqualTo(budget);
        assertThat(counter.countTokens(numbered.apply(chunks.subList(0, 10)))).isGreaterThan(budget);
        assertThat(kept).extracting(ScoredChunk::chunkId).containsExactlyElementsOf(
            chunks.subList(0, kept.size()).stream().map(ScoredChunk::chunkId).toList());
    }

    @Test
    void returnsEmptyWhenNothingFits() {
        var kept = truncate(List.of(chunkWithText("c1", "alpha beta gamma")), 1);

        assertThat(kept).isEmpty();
    }

    private List<ScoredChunk> truncate(List<ScoredChunk> chunks, int maxTokens) {
        return truncator.truncate(chunks, maxTokens, ChunkBudgetTruncatorTest::render, ChunkBudgetTruncatorTest::approxKey);
    }

    private static String render(List<ScoredChunk> chunks) {
        return chunks.stream()
            .map(chunk -> "[" + chunk.chunkId() + "] " + chunk.chunk().text())
            .collect(Collectors.joining("\n"));
    }

    private static String approxKey(ScoredChunk chunk) {
        return chunk.chunk().text();
    }

    private static ScoredChunk chunkWithText(String id, String text) {
        return new ScoredChunk(id, new Chunk(id, "doc", text, text.length(), 0, Map.of()), 1.0d);
    }
}
