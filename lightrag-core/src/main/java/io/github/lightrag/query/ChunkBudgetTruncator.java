package io.github.lightrag.query;

import io.github.lightrag.model.TokenCounter;
import io.github.lightrag.types.ScoredChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Two-stage, render-verified chunk truncation: whole chunks only, never a mid-text prefix
 * (upstream {@code _truncate_chunks_for_unified_context}, utils.py:7825-7874).
 */
final class ChunkBudgetTruncator {
    private final TokenCounter counter;

    ChunkBudgetTruncator(TokenCounter counter) {
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    List<ScoredChunk> truncate(
        List<ScoredChunk> chunks,
        int maxTokens,
        Function<List<ScoredChunk>, String> renderer,
        Function<ScoredChunk, String> approxKey
    ) {
        if (maxTokens <= 0 || chunks.isEmpty()) {
            return List.of();
        }
        // Stage 1: greedy prefix over the per-chunk {content, content_headings} projection only.
        // reference_id cannot be counted here — it is derived from the survivor set, which is
        // exactly what this stage decides (upstream utils.py:7825-7874).
        var approx = approximatePrefix(chunks, maxTokens, approxKey);
        // Stage 2: re-render the candidates through the real renderer (same call the prompt uses,
        // reference ids recomputed for this exact candidate list) and shrink k until it fits.
        var k = approx.size();
        while (k > 0) {
            if (counter.countTokens(renderer.apply(approx.subList(0, k))) <= maxTokens) {
                break;
            }
            k--;
        }
        return List.copyOf(approx.subList(0, k));
    }

    private List<ScoredChunk> approximatePrefix(
        List<ScoredChunk> chunks,
        int maxTokens,
        Function<ScoredChunk, String> approxKey
    ) {
        var kept = new ArrayList<ScoredChunk>();
        var joined = "";
        for (var chunk : chunks) {
            var key = approxKey.apply(chunk);
            var candidate = kept.isEmpty() ? key : joined + "\n" + key;
            if (counter.countTokens(candidate) > maxTokens) {
                break;
            }
            kept.add(chunk);
            joined = candidate;
        }
        return List.copyOf(kept);
    }
}
