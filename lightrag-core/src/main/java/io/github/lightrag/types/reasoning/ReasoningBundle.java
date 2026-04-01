package io.github.lightrag.types.reasoning;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.ScoredChunk;

import java.util.List;
import java.util.Objects;

public record ReasoningBundle(
    QueryRequest request,
    List<ReasoningPath> selectedPaths,
    List<HopEvidence> hopEvidences,
    List<ScoredChunk> fallbackChunks
) {
    public ReasoningBundle {
        request = Objects.requireNonNull(request, "request");
        selectedPaths = List.copyOf(Objects.requireNonNull(selectedPaths, "selectedPaths"));
        hopEvidences = List.copyOf(Objects.requireNonNull(hopEvidences, "hopEvidences"));
        fallbackChunks = List.copyOf(Objects.requireNonNull(fallbackChunks, "fallbackChunks"));
    }
}
