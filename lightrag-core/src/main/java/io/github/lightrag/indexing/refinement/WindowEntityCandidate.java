package io.github.lightrag.indexing.refinement;

import io.github.lightrag.ontology.AttributeValues;

import java.util.List;
import java.util.Map;

public record WindowEntityCandidate(
    String name,
    String type,
    String description,
    List<String> aliases,
    List<Integer> supportingChunkIndexes,
    Map<String, Object> attributes
) {
    public WindowEntityCandidate {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        supportingChunkIndexes = supportingChunkIndexes == null ? List.of() : List.copyOf(supportingChunkIndexes);
        attributes = AttributeValues.merge(Map.of(), attributes);
    }

    public WindowEntityCandidate(
        String name,
        String type,
        String description,
        List<String> aliases,
        List<Integer> supportingChunkIndexes
    ) {
        this(name, type, description, aliases, supportingChunkIndexes, Map.of());
    }
}
