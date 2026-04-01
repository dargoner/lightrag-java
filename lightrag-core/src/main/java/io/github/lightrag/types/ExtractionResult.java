package io.github.lightrag.types;

import java.util.List;
import java.util.Objects;

public record ExtractionResult(
    List<ExtractedEntity> entities,
    List<ExtractedRelation> relations,
    List<String> warnings
) {
    public ExtractionResult {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
