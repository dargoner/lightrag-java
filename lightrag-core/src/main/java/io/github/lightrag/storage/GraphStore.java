package io.github.lightrag.storage;

import io.github.lightrag.indexing.RelationCanonicalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface GraphStore {
    void saveEntity(EntityRecord entity);

    void saveRelation(RelationRecord relation);

    default void saveEntities(List<EntityRecord> entities) {
        Objects.requireNonNull(entities, "entities");
        for (var entity : entities) {
            saveEntity(entity);
        }
    }

    default void saveRelations(List<RelationRecord> relations) {
        Objects.requireNonNull(relations, "relations");
        for (var relation : relations) {
            saveRelation(relation);
        }
    }

    Optional<EntityRecord> loadEntity(String entityId);

    Optional<RelationRecord> loadRelation(String relationId);

    default List<EntityRecord> loadEntities(List<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        var entities = new ArrayList<EntityRecord>(entityIds.size());
        for (var entityId : entityIds) {
            loadEntity(entityId).ifPresent(entities::add);
        }
        return entities;
    }

    default List<RelationRecord> loadRelations(List<String> relationIds) {
        Objects.requireNonNull(relationIds, "relationIds");
        var relations = new ArrayList<RelationRecord>(relationIds.size());
        for (var relationId : relationIds) {
            loadRelation(relationId).ifPresent(relations::add);
        }
        return relations;
    }

    List<EntityRecord> allEntities();

    List<RelationRecord> allRelations();

    List<RelationRecord> findRelations(String entityId);

    default Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
        Objects.requireNonNull(entityIds, "entityIds");
        var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
        for (var entityId : entityIds) {
            relationsByEntityId.put(entityId, List.copyOf(findRelations(entityId)));
        }
        return java.util.Collections.unmodifiableMap(relationsByEntityId);
    }

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
        String relationId,
        String srcId,
        String tgtId,
        String keywords,
        String description,
        double weight,
        String sourceId,
        String filePath
    ) {
        public RelationRecord(
            String relationId,
            String srcId,
            String tgtId,
            String keywords,
            String description,
            double weight,
            List<String> sourceChunkIds
        ) {
            this(
                relationId,
                srcId,
                tgtId,
                keywords,
                description,
                weight,
                RelationCanonicalizer.joinValues(sourceChunkIds),
                ""
            );
        }

        public RelationRecord {
            relationId = Objects.requireNonNull(relationId, "relationId");
            srcId = Objects.requireNonNull(srcId, "srcId");
            tgtId = Objects.requireNonNull(tgtId, "tgtId");
            keywords = Objects.requireNonNull(keywords, "keywords");
            description = Objects.requireNonNull(description, "description");
            sourceId = sourceId == null ? "" : sourceId;
            filePath = filePath == null ? "" : filePath;
        }

        public String id() {
            return relationId;
        }

        public String sourceEntityId() {
            return srcId;
        }

        public String targetEntityId() {
            return tgtId;
        }

        public String type() {
            return keywords;
        }

        public List<String> sourceChunkIds() {
            return RelationCanonicalizer.splitValues(sourceId);
        }

        public List<String> filePaths() {
            return RelationCanonicalizer.splitValues(filePath);
        }
    }
}
