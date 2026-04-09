package io.github.lightrag.indexing.refinement;

import java.util.List;

public record WindowExtractionResponse(
    List<WindowEntityCandidate> entities,
    List<WindowRelationCandidate> relations,
    List<String> warnings
) {
    public WindowExtractionResponse {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
