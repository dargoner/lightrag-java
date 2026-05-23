package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.GraphStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ArcadeGraphStore extends ArcadeStoreSupport implements GraphStore {
    public ArcadeGraphStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("name", record.name());
        properties.put("type", record.type());
        properties.put("description", record.description());
        properties.put("aliases", ArcadeJsonCodec.writeStringList(record.aliases()));
        properties.put("sourceChunkIds", ArcadeJsonCodec.writeStringList(record.sourceChunkIds()));
        upsertByWorkspaceId("Entity", "id", record.id(), properties);
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        var properties = new LinkedHashMap<String, Object>();
        properties.put("srcId", record.srcId());
        properties.put("tgtId", record.tgtId());
        properties.put("keywords", record.keywords());
        properties.put("description", record.description());
        properties.put("weight", record.weight());
        properties.put("sourceId", record.sourceId());
        properties.put("filePath", record.filePath());
        upsertByWorkspaceId("Relation", "id", record.id(), properties);
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        return first("SELECT id, name, type, description, aliases, sourceChunkIds FROM Entity WHERE workspaceId = ? AND id = ? LIMIT 1", workspaceId, entityId)
            .map(this::readEntity);
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        return first(selectRelationBase() + " WHERE workspaceId = ? AND id = ? LIMIT 1", workspaceId, relationId)
            .map(this::readRelation);
    }

    @Override
    public List<EntityRecord> allEntities() {
        return query("SELECT id, name, type, description, aliases, sourceChunkIds FROM Entity WHERE workspaceId = ? ORDER BY id", workspaceId)
            .stream()
            .map(this::readEntity)
            .toList();
    }

    @Override
    public List<RelationRecord> allRelations() {
        return query(selectRelationBase() + " WHERE workspaceId = ? ORDER BY id", workspaceId)
            .stream()
            .map(this::readRelation)
            .toList();
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        return query(
            selectRelationBase() + " WHERE workspaceId = ? AND (srcId = ? OR tgtId = ?) ORDER BY id",
            workspaceId,
            entityId,
            entityId
        ).stream().map(this::readRelation).toList();
    }

    void deleteAll() {
        deleteWorkspaceRows("Relation");
        deleteWorkspaceRows("Entity");
    }

    private String selectRelationBase() {
        return "SELECT id, srcId, tgtId, keywords, description, weight, sourceId, filePath FROM Relation";
    }

    private EntityRecord readEntity(Map<String, Object> row) {
        return new EntityRecord(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "name"),
            ArcadeRecordMapper.string(row, "type"),
            ArcadeRecordMapper.string(row, "description"),
            ArcadeRecordMapper.stringList(row, "aliases"),
            ArcadeRecordMapper.stringList(row, "sourceChunkIds")
        );
    }

    private RelationRecord readRelation(Map<String, Object> row) {
        return new RelationRecord(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "srcId"),
            ArcadeRecordMapper.string(row, "tgtId"),
            ArcadeRecordMapper.string(row, "keywords"),
            ArcadeRecordMapper.string(row, "description"),
            ArcadeRecordMapper.decimal(row, "weight"),
            ArcadeRecordMapper.string(row, "sourceId"),
            ArcadeRecordMapper.string(row, "filePath")
        );
    }
}
