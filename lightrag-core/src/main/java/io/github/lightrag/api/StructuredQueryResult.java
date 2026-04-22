package io.github.lightrag.api;

import java.util.List;
import java.util.Objects;

public record StructuredQueryResult(
    String answer,
    List<QueryResult.Context> contexts,
    List<QueryResult.Reference> references,
    List<StructuredQueryEntity> entities,
    List<StructuredQueryRelation> relations,
    List<StructuredQueryChunk> chunks
) {
    public StructuredQueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }
}
