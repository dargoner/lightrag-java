package io.github.lightrag.indexing.refinement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class DefaultRefinementWindowResolver implements RefinementWindowResolver {
    @Override
    public Optional<RefinementWindow> resolve(
        List<PrimaryChunkExtraction> extractions,
        int index,
        GapAssessment assessment
    ) {
        var batch = List.copyOf(Objects.requireNonNull(extractions, "extractions"));
        var resolvedAssessment = Objects.requireNonNull(assessment, "assessment");
        if (index < 0 || index >= batch.size()) {
            throw new IllegalArgumentException("index is out of range");
        }
        if (!resolvedAssessment.requiresRefinement() || resolvedAssessment.recommendedScope() != RefinementScope.ADJACENT) {
            return Optional.empty();
        }

        int start = Math.max(0, index - 1);
        int end = Math.min(batch.size() - 1, index + 1);
        var chunks = batch.subList(start, end + 1).stream()
            .map(PrimaryChunkExtraction::chunk)
            .toList();
        return Optional.of(new RefinementWindow(
            chunks.get(0).documentId(),
            chunks,
            index - start,
            RefinementScope.ADJACENT,
            estimateTokens(chunks)
        ));
    }

    private static int estimateTokens(List<io.github.lightrag.types.Chunk> chunks) {
        return chunks.stream()
            .map(io.github.lightrag.types.Chunk::text)
            .mapToInt(DefaultRefinementWindowResolver::estimateTokens)
            .sum();
    }

    private static int estimateTokens(String text) {
        var normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            return 0;
        }
        return (int) normalized.codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint))
            .count();
    }
}
