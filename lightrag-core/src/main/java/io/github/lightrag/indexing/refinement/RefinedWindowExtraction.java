package io.github.lightrag.indexing.refinement;

import java.util.List;
import java.util.Objects;

public record RefinedWindowExtraction(
    List<RefinedEntityPatch> entityPatches,
    List<RefinedRelationPatch> relationPatches,
    List<String> warnings,
    boolean hasPatches
) {
    public RefinedWindowExtraction {
        entityPatches = List.copyOf(Objects.requireNonNull(entityPatches, "entityPatches"));
        relationPatches = List.copyOf(Objects.requireNonNull(relationPatches, "relationPatches"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
