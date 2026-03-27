package io.github.lightragjava.types.reasoning;

import java.util.List;
import java.util.Objects;

public record HopEvidence(
    int hopIndex,
    String sourceEntityName,
    String relationType,
    String targetEntityName,
    List<String> evidenceTexts
) {
    public HopEvidence {
        if (hopIndex <= 0) {
            throw new IllegalArgumentException("hopIndex must be positive");
        }
        sourceEntityName = Objects.requireNonNull(sourceEntityName, "sourceEntityName").strip();
        relationType = Objects.requireNonNull(relationType, "relationType").strip();
        targetEntityName = Objects.requireNonNull(targetEntityName, "targetEntityName").strip();
        evidenceTexts = List.copyOf(Objects.requireNonNull(evidenceTexts, "evidenceTexts"));
    }
}
