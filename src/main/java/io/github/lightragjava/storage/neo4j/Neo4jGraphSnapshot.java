package io.github.lightragjava.storage.neo4j;

import io.github.lightragjava.storage.GraphStore;

import java.util.List;
import java.util.Objects;

public record Neo4jGraphSnapshot(
    List<GraphStore.EntityRecord> entities,
    List<GraphStore.RelationRecord> relations
) {
    public Neo4jGraphSnapshot {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
    }
}
