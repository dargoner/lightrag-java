package io.github.lightrag.storage.memory;

import io.github.lightrag.storage.GraphStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryGraphStore implements GraphStore {
    private final Map<String, EntityRecord> entities = new TreeMap<>();
    private final Map<String, RelationRecord> relations = new TreeMap<>();
    private final Map<String, Set<String>> relationIdsByEntity = new HashMap<>();
    private final ReadWriteLock lock;

    public InMemoryGraphStore() {
        this(new ReentrantReadWriteLock(true));
    }

    public InMemoryGraphStore(ReadWriteLock lock) {
        this.lock = Objects.requireNonNull(lock, "lock");
    }

    @Override
    public void saveEntity(EntityRecord entity) {
        var record = Objects.requireNonNull(entity, "entity");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            entities.put(record.id(), record);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void saveRelation(RelationRecord relation) {
        var record = Objects.requireNonNull(relation, "relation");
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            var previous = relations.put(record.id(), record);
            if (previous != null) {
                removeRelationEndpoint(previous.srcId(), previous.id());
                removeRelationEndpoint(previous.tgtId(), previous.id());
            }
            addRelationEndpoint(record.srcId(), record.id());
            addRelationEndpoint(record.tgtId(), record.id());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<EntityRecord> loadEntity(String entityId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(entities.get(Objects.requireNonNull(entityId, "entityId")));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Optional<RelationRecord> loadRelation(String relationId) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return Optional.ofNullable(relations.get(Objects.requireNonNull(relationId, "relationId")));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<EntityRecord> allEntities() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return List.copyOf(entities.values());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<RelationRecord> allRelations() {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return List.copyOf(relations.values());
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public List<RelationRecord> findRelations(String entityId) {
        var targetEntityId = Objects.requireNonNull(entityId, "entityId");
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var relationIds = relationIdsByEntity.get(targetEntityId);
            if (relationIds == null || relationIds.isEmpty()) {
                return List.of();
            }
            return relationIds.stream()
                .map(relations::get)
                .filter(Objects::nonNull)
                .toList();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
        var ids = List.copyOf(Objects.requireNonNull(entityIds, "entityIds"));
        var readLock = lock.readLock();
        readLock.lock();
        try {
            var relationsByEntityId = new LinkedHashMap<String, List<RelationRecord>>();
            for (var entityId : ids) {
                var relationIds = relationIdsByEntity.get(entityId);
                if (relationIds == null || relationIds.isEmpty()) {
                    relationsByEntityId.put(entityId, List.of());
                    continue;
                }
                var entityRelations = new ArrayList<RelationRecord>(relationIds.size());
                for (var relationId : relationIds) {
                    var relation = relations.get(relationId);
                    if (relation != null) {
                        entityRelations.add(relation);
                    }
                }
                relationsByEntityId.put(entityId, List.copyOf(entityRelations));
            }
            return Collections.unmodifiableMap(relationsByEntityId);
        } finally {
            readLock.unlock();
        }
    }

    public List<EntityRecord> snapshotEntities() {
        return allEntities();
    }

    public List<RelationRecord> snapshotRelations() {
        return allRelations();
    }

    public void restore(List<EntityRecord> entitySnapshot, List<RelationRecord> relationSnapshot) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            entities.clear();
            relations.clear();
            relationIdsByEntity.clear();

            for (var entity : Objects.requireNonNull(entitySnapshot, "entitySnapshot")) {
                entities.put(entity.id(), entity);
            }
            for (var relation : Objects.requireNonNull(relationSnapshot, "relationSnapshot")) {
                relations.put(relation.id(), relation);
                addRelationEndpoint(relation.srcId(), relation.id());
                addRelationEndpoint(relation.tgtId(), relation.id());
            }
        } finally {
            writeLock.unlock();
        }
    }

    private void addRelationEndpoint(String entityId, String relationId) {
        relationIdsByEntity.computeIfAbsent(entityId, ignored -> new TreeSet<>()).add(relationId);
    }

    private void removeRelationEndpoint(String entityId, String relationId) {
        var relationIds = relationIdsByEntity.get(entityId);
        if (relationIds == null) {
            return;
        }
        relationIds.remove(relationId);
        if (relationIds.isEmpty()) {
            relationIdsByEntity.remove(entityId);
        }
    }
}
