package io.github.lightrag.storage.neo4j;

import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Neo4jGraphStorageAdapterTest {
    @Test
    void capturesPreImageWithPointReadsOnly() {
        var projection = new RecordingProjection();
        var adapter = new Neo4jGraphStorageAdapter(projection);
        projection.saveEntity(entity("e1", "before"));
        projection.saveRelation(relation("r1", "e1", "e2", "before"));
        projection.clearCalls();

        var preImage = adapter.capturePreImage(List.of("e1", "e-new"), List.of("r1", "r-new"));

        assertThat(preImage).isPresent();
        assertThat(projection.calls()).containsExactly(
            "loadEntities[e1, e-new]",
            "loadRelations[r1, r-new]"
        );
    }

    @Test
    void restoresPresentIdsBySavingAndAbsentIdsByDeleting() {
        var projection = new RecordingProjection();
        var adapter = new Neo4jGraphStorageAdapter(projection);
        var originalEntity = entity("e1", "before");
        var endpointEntity = entity("e2", "endpoint");
        var originalRelation = relation("r1", "e1", "e2", "before");
        projection.saveEntity(originalEntity);
        projection.saveEntity(endpointEntity);
        projection.saveRelation(originalRelation);

        var preImage = adapter.capturePreImage(List.of("e1", "e-new"), List.of("r1", "r-new")).orElseThrow();

        projection.saveEntity(entity("e1", "attempt"));
        projection.saveEntity(entity("e-new", "attempt"));
        projection.saveRelation(relation("r1", "e1", "e2", "attempt"));
        projection.saveRelation(relation("r-new", "e1", "e-new", "attempt"));
        projection.clearCalls();

        adapter.restorePreImage(preImage);

        assertThat(projection.calls()).containsExactly(
            "deleteEntities[e-new]",
            "saveEntities[e1]",
            "deleteRelations[r-new]",
            "saveRelations[r1]"
        );
        assertThat(projection.allEntities()).containsExactlyInAnyOrder(originalEntity, endpointEntity);
        assertThat(projection.allRelations()).containsExactly(originalRelation);
        assertThat(projection.placeholderIds()).isEmpty();
    }

    @Test
    void restoresExactPreImageForSharedEndpointsAndHoles() {
        var projection = new RecordingProjection();
        var adapter = new Neo4jGraphStorageAdapter(projection);
        var firstEndpoint = entity("e1", "shared");
        var secondEndpoint = entity("e2", "shared");
        var thirdEntity = entity("e3", "unrelated");
        var originalRelation = relation("r1", "e1", "e2", "shared");
        projection.saveEntity(firstEndpoint);
        projection.saveEntity(secondEndpoint);
        projection.saveEntity(thirdEntity);
        projection.saveRelation(originalRelation);

        var preImage = adapter.capturePreImage(
            List.of("e1", "e2", "e3", "e-new", "e-ghost"),
            List.of("r1", "r-new", "r-ghost")
        ).orElseThrow();

        projection.saveEntity(entity("e1", "attempt"));
        projection.saveEntity(entity("e-new", "attempt"));
        projection.saveRelation(relation("r1", "e1", "e2", "attempt"));
        projection.saveRelation(relation("r-new", "e1", "e-new", "attempt"));
        projection.saveRelation(relation("r-ghost", "e1", "e-ghost", "attempt"));
        projection.clearCalls();

        adapter.restorePreImage(preImage);

        assertThat(projection.allEntities())
            .containsExactlyInAnyOrder(firstEndpoint, secondEndpoint, thirdEntity);
        assertThat(projection.allRelations()).containsExactly(originalRelation);
        assertThat(projection.storedEntity("e1")).contains(firstEndpoint);
        assertThat(projection.storedRelation("r1")).contains(originalRelation);
        assertThat(projection.placeholderIds()).isEmpty();
    }

    @Test
    void keepsRelationHolesUnmaterializedDuringRestore() {
        var projection = new RecordingProjection();
        var adapter = new Neo4jGraphStorageAdapter(projection);
        var endpoint = entity("e1", "before");
        var relationWithHole = relation("r1", "e1", "e-ghost", "before");
        projection.saveEntity(endpoint);
        projection.saveRelation(relationWithHole);

        var preImage = adapter.capturePreImage(List.of("e1", "e-ghost"), List.of("r1")).orElseThrow();

        projection.saveEntity(entity("e1", "attempt"));
        projection.saveRelation(relation("r1", "e1", "e-ghost", "attempt"));
        projection.clearCalls();

        adapter.restorePreImage(preImage);

        assertThat(projection.calls()).containsExactly(
            "deleteEntities[e-ghost]",
            "saveEntities[e1]",
            "saveRelations[r1]"
        );
        assertThat(projection.allEntities()).containsExactly(endpoint);
        assertThat(projection.allRelations()).containsExactly(relationWithHole);
        assertThat(projection.placeholderIds()).containsExactly("e-ghost");
    }

    @Test
    void rejectsForeignPreImagePayload() {
        var adapter = new Neo4jGraphStorageAdapter(new RecordingProjection());

        assertThatThrownBy(() -> adapter.restorePreImage(new GraphStorageAdapter.PreImage() {
        }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unexpected pre-image payload");
    }

    private static GraphStore.EntityRecord entity(String id, String description) {
        return new GraphStore.EntityRecord(
            id,
            "name-" + id,
            "type-" + id,
            description,
            List.of("alias-" + id),
            List.of("chunk-" + id)
        );
    }

    private static GraphStore.RelationRecord relation(String id, String srcId, String tgtId, String description) {
        return new GraphStore.RelationRecord(
            id,
            srcId,
            tgtId,
            "keywords-" + id,
            description,
            0.5d,
            List.of("chunk-" + id)
        );
    }

    private static final class RecordingProjection implements Neo4jGraphStorageAdapter.Projection {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Set<String> placeholderIds = new LinkedHashSet<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private final List<String> calls = new ArrayList<>();

        @Override
        public void saveEntity(GraphStore.EntityRecord entity) {
            calls.add("saveEntity[" + entity.id() + "]");
            entities.put(entity.id(), entity);
            placeholderIds.remove(entity.id());
        }

        @Override
        public void saveEntities(List<GraphStore.EntityRecord> records) {
            calls.add("saveEntities" + idsOfEntities(records));
            for (var record : records) {
                entities.put(record.id(), record);
                placeholderIds.remove(record.id());
            }
        }

        @Override
        public void saveRelation(GraphStore.RelationRecord relation) {
            calls.add("saveRelation[" + relation.id() + "]");
            relations.put(relation.id(), relation);
            ensureEndpoint(relation.srcId());
            ensureEndpoint(relation.tgtId());
        }

        @Override
        public void saveRelations(List<GraphStore.RelationRecord> records) {
            calls.add("saveRelations" + idsOfRelations(records));
            for (var record : records) {
                relations.put(record.id(), record);
                ensureEndpoint(record.srcId());
                ensureEndpoint(record.tgtId());
            }
        }

        @Override
        public Optional<GraphStore.EntityRecord> loadEntity(String entityId) {
            return Optional.ofNullable(entities.get(entityId));
        }

        @Override
        public List<GraphStore.EntityRecord> loadEntities(List<String> entityIds) {
            calls.add("loadEntities" + List.copyOf(entityIds));
            return entityIds.stream().map(entities::get).filter(Objects::nonNull).toList();
        }

        @Override
        public Optional<GraphStore.RelationRecord> loadRelation(String relationId) {
            return Optional.ofNullable(relations.get(relationId));
        }

        @Override
        public List<GraphStore.RelationRecord> loadRelations(List<String> relationIds) {
            calls.add("loadRelations" + List.copyOf(relationIds));
            return relationIds.stream().map(relations::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<GraphStore.EntityRecord> allEntities() {
            calls.add("allEntities");
            return entities.values().stream()
                .sorted(Comparator.comparing(GraphStore.EntityRecord::id))
                .toList();
        }

        @Override
        public List<GraphStore.RelationRecord> allRelations() {
            calls.add("allRelations");
            return relations.values().stream()
                .sorted(Comparator.comparing(GraphStore.RelationRecord::id))
                .toList();
        }

        @Override
        public List<GraphStore.RelationRecord> findRelations(String entityId) {
            return relations.values().stream()
                .filter(relation -> relation.srcId().equals(entityId) || relation.tgtId().equals(entityId))
                .toList();
        }

        @Override
        public int deleteEntities(List<String> entityIds) {
            calls.add("deleteEntities" + List.copyOf(entityIds));
            var deleted = 0;
            for (var entityId : entityIds) {
                var removedEntity = entities.remove(entityId) != null;
                var removedPlaceholder = placeholderIds.remove(entityId);
                if (removedEntity || removedPlaceholder) {
                    deleted++;
                }
                relations.values().removeIf(relation ->
                    relation.srcId().equals(entityId) || relation.tgtId().equals(entityId));
            }
            return deleted;
        }

        @Override
        public int deleteRelations(List<String> relationIds) {
            calls.add("deleteRelations" + List.copyOf(relationIds));
            var deleted = 0;
            for (var relationId : relationIds) {
                if (relations.remove(relationId) != null) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public Neo4jGraphSnapshot captureSnapshot() {
            calls.add("captureSnapshot");
            return new Neo4jGraphSnapshot(allEntities(), allRelations());
        }

        @Override
        public void restore(Neo4jGraphSnapshot snapshot) {
            calls.add("restore");
            entities.clear();
            placeholderIds.clear();
            relations.clear();
            snapshot.entities().forEach(this::saveEntity);
            snapshot.relations().forEach(this::saveRelation);
        }

        @Override
        public void close() {
            calls.add("close");
        }

        Optional<GraphStore.EntityRecord> storedEntity(String entityId) {
            return Optional.ofNullable(entities.get(entityId));
        }

        Optional<GraphStore.RelationRecord> storedRelation(String relationId) {
            return Optional.ofNullable(relations.get(relationId));
        }

        Set<String> placeholderIds() {
            return Set.copyOf(placeholderIds);
        }

        List<String> calls() {
            return List.copyOf(calls);
        }

        void clearCalls() {
            calls.clear();
        }

        private void ensureEndpoint(String entityId) {
            if (!entities.containsKey(entityId)) {
                placeholderIds.add(entityId);
            }
        }

        private static List<String> idsOfEntities(List<GraphStore.EntityRecord> records) {
            return records.stream().map(GraphStore.EntityRecord::id).toList();
        }

        private static List<String> idsOfRelations(List<GraphStore.RelationRecord> records) {
            return records.stream().map(GraphStore.RelationRecord::id).toList();
        }
    }
}
