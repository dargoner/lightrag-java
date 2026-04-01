package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface GraphStore {
    void saveEntity(EntityRecord entity);

    void saveRelation(RelationRecord relation);

    Optional<EntityRecord> loadEntity(String entityId);

    Optional<RelationRecord> loadRelation(String relationId);

    List<EntityRecord> allEntities();

    List<RelationRecord> allRelations();

    List<RelationRecord> findRelations(String entityId);

    record EntityRecord(
        String id,
        String name,
        String type,
        String description,
        List<String> aliases,
        List<String> sourceChunkIds
    ) {
        public EntityRecord {
            id = Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            description = Objects.requireNonNull(description, "description");
            aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
            sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        }
    }

    record RelationRecord(
        String id,
        String sourceEntityId,
        String targetEntityId,
        String type,
        String description,
        double weight,
        List<String> sourceChunkIds
    ) {
        public RelationRecord {
            id = Objects.requireNonNull(id, "id");
            sourceEntityId = Objects.requireNonNull(sourceEntityId, "sourceEntityId");
            targetEntityId = Objects.requireNonNull(targetEntityId, "targetEntityId");
            type = Objects.requireNonNull(type, "type");
            description = Objects.requireNonNull(description, "description");
            sourceChunkIds = List.copyOf(Objects.requireNonNull(sourceChunkIds, "sourceChunkIds"));
        }
    }
}
