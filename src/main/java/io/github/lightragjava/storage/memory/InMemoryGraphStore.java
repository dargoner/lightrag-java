package io.github.lightragjava.storage.memory;

import io.github.lightragjava.storage.GraphStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

public final class InMemoryGraphStore implements GraphStore {
    private final ConcurrentNavigableMap<String, EntityRecord> entities = new ConcurrentSkipListMap<>();
    private final ConcurrentNavigableMap<String, RelationRecord> relations = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<String, Set<String>> relationIdsByEntity = new ConcurrentHashMap<>();

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        entities.put(record.id(), record);
    }

    @Override
    public synchronized void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        var previous = relations.put(record.id(), record);
        if (previous != null) {
            removeRelationEndpoint(previous.sourceEntityId(), previous.id());
            removeRelationEndpoint(previous.targetEntityId(), previous.id());
        }
        addRelationEndpoint(record.sourceEntityId(), record.id());
        addRelationEndpoint(record.targetEntityId(), record.id());
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        return Optional.ofNullable(entities.get(Objects.requireNonNull(entityId, "entityId")));
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        return Optional.ofNullable(relations.get(Objects.requireNonNull(relationId, "relationId")));
    }

    @Override
    public List<EntityRecord> allEntities() {
        return List.copyOf(entities.values());
    }

    @Override
    public List<RelationRecord> allRelations() {
        return List.copyOf(relations.values());
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var targetEntityId = Objects.requireNonNull(entityId, "entityId");
        var relationIds = relationIdsByEntity.get(targetEntityId);
        if (relationIds == null || relationIds.isEmpty()) {
            return List.of();
        }
        return relationIds.stream()
            .map(relations::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private void addRelationEndpoint(String entityId, String relationId) {
        relationIdsByEntity.computeIfAbsent(entityId, ignored -> new ConcurrentSkipListSet<>()).add(relationId);
    }

    private void removeRelationEndpoint(String entityId, String relationId) {
        relationIdsByEntity.computeIfPresent(entityId, (ignored, relationIds) -> {
            relationIds.remove(relationId);
            return relationIds.isEmpty() ? null : relationIds;
        });
    }
}
