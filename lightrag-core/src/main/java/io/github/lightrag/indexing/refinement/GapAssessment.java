package io.github.lightrag.indexing.refinement;

import java.util.List;
import java.util.Objects;

public record GapAssessment(
    boolean requiresRefinement,
    List<String> prescreenSignals,
    List<String> qualitySignals,
    RefinementScope recommendedScope
) {
    public GapAssessment {
        prescreenSignals = List.copyOf(Objects.requireNonNull(prescreenSignals, "prescreenSignals"));
        qualitySignals = List.copyOf(Objects.requireNonNull(qualitySignals, "qualitySignals"));
        recommendedScope = Objects.requireNonNull(recommendedScope, "recommendedScope");
    }
}
