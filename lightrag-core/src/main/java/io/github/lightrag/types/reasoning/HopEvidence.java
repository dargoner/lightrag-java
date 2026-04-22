package io.github.lightrag.types.reasoning;

import java.util.List;
import java.util.Objects;

public record HopEvidence(
    int hopIndex,
    String srcId,
    String relationId,
    String tgtId,
    String keywords,
    String description,
    double weight,
    List<String> evidenceTexts
) {
    public HopEvidence {
        if (hopIndex <= 0) {
            throw new IllegalArgumentException("hopIndex must be positive");
        }
        srcId = Objects.requireNonNull(srcId, "srcId").strip();
        relationId = Objects.requireNonNull(relationId, "relationId").strip();
        tgtId = Objects.requireNonNull(tgtId, "tgtId").strip();
        keywords = Objects.requireNonNull(keywords, "keywords").strip();
        description = description == null ? "" : description.strip();
        if (!Double.isFinite(weight)) {
            throw new IllegalArgumentException("weight must be finite");
        }
        evidenceTexts = List.copyOf(Objects.requireNonNull(evidenceTexts, "evidenceTexts"));
    }
}
