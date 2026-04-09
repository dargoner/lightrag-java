package io.github.lightrag.indexing.refinement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class DefaultExtractionGapDetector implements ExtractionGapDetector {
    private static final Pattern TRAILING_RELATION_KEYWORD = Pattern.compile(".*(依赖|通过|使用|调用|用于|先|再)$");

    @Override
    public GapAssessment assess(List<PrimaryChunkExtraction> extractions, int index) {
        var batch = List.copyOf(Objects.requireNonNull(extractions, "extractions"));
        if (index < 0 || index >= batch.size()) {
            throw new IllegalArgumentException("index is out of range");
        }

        var primary = batch.get(index);
        var prescreenSignals = new ArrayList<String>();
        if (TRAILING_RELATION_KEYWORD.matcher(primary.chunk().text().strip()).matches()) {
            prescreenSignals.add("trailing_relation_keyword");
        }

        var qualitySignals = new ArrayList<String>();
        if (primary.extraction().entities().size() >= 2 && primary.extraction().relations().isEmpty()) {
            qualitySignals.add("entities_without_relations");
        }

        var requiresRefinement = !prescreenSignals.isEmpty() && !qualitySignals.isEmpty();
        return new GapAssessment(
            requiresRefinement,
            List.copyOf(prescreenSignals),
            List.copyOf(qualitySignals),
            requiresRefinement ? RefinementScope.ADJACENT : RefinementScope.NONE
        );
    }
}
