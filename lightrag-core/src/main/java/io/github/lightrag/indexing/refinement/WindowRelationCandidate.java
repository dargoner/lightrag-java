package io.github.lightrag.indexing.refinement;

import io.github.lightrag.ontology.AttributeValues;

import java.util.List;
import java.util.Map;

public record WindowRelationCandidate(
    String sourceEntityName,
    String targetEntityName,
    String keywords,
    String description,
    Double weight,
    List<Integer> supportingChunkIndexes,
    Map<String, Object> attributes
) {
    public WindowRelationCandidate {
        supportingChunkIndexes = supportingChunkIndexes == null ? List.of() : List.copyOf(supportingChunkIndexes);
        attributes = AttributeValues.merge(Map.of(), attributes);
    }

    public WindowRelationCandidate(
        String sourceEntityName,
        String targetEntityName,
        String keywords,
        String description,
        Double weight,
        List<Integer> supportingChunkIndexes
    ) {
        this(sourceEntityName, targetEntityName, keywords, description, weight, supportingChunkIndexes, Map.of());
    }
}
