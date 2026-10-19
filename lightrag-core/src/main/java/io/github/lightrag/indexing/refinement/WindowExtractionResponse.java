package io.github.lightrag.indexing.refinement;

import io.github.lightrag.ontology.UnmatchedLabel;

import java.util.List;

public record WindowExtractionResponse(
    List<WindowEntityCandidate> entities,
    List<WindowRelationCandidate> relations,
    List<String> warnings,
    List<UnmatchedLabel> attributeDiagnostics
) {
    public WindowExtractionResponse {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        attributeDiagnostics = attributeDiagnostics == null ? List.of() : List.copyOf(attributeDiagnostics);
    }

    public WindowExtractionResponse(
        List<WindowEntityCandidate> entities,
        List<WindowRelationCandidate> relations,
        List<String> warnings
    ) {
        this(entities, relations, warnings, List.of());
    }

    public WindowExtractionResponse(
        List<WindowEntityCandidate> entities,
        List<WindowRelationCandidate> relations
    ) {
        this(entities, relations, List.of(), List.of());
    }
}
