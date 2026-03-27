package io.github.lightragjava.types.reasoning;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.ScoredChunk;

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
