package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultPathRetrieverTest {
    @Test
    void reasoningPathRejectsInvalidShape() {
        assertThatThrownBy(() -> new io.github.lightrag.types.reasoning.ReasoningPath(
            List.of("entity:atlas"),
            List.of("relation:1"),
            List.of("chunk-1"),
            1,
            0.0d
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityIds");
    }

    @Test
    void retrievesOneHopAndTwoHopPathsFromSeedEntity() {
        var atlas = entity("entity:atlas", "Atlas", List.of("chunk-1"));
        var graphStore = entity("entity:graphstore", "GraphStore", List.of("chunk-1", "chunk-2"));
        var team = entity("entity:team", "KnowledgeGraphTeam", List.of("chunk-2"));
        var dependsOn = relation("relation:atlas|depends_on|graphstore", atlas.id(), graphStore.id(), "depends_on", List.of("chunk-1"));
        var ownedBy = relation("relation:graphstore|owned_by|team", graphStore.id(), team.id(), "owned_by", List.of("chunk-2"));
        var store = new FakeGraphStore(List.of(atlas, graphStore, team), List.of(dependsOn, ownedBy));
        var retriever = new DefaultPathRetriever(store, 4);

        var result = retriever.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .maxHop(2)
            .pathTopK(3)
            .build(), seedContext(atlas, dependsOn));

        assertThat(result.paths()).hasSize(2);
        assertThat(result.paths())
            .extracting(path -> path.hopCount())
            .containsExactly(1, 2);
        assertThat(result.paths().get(0).entityIds())
            .containsExactly(atlas.id(), graphStore.id());
        assertThat(result.paths().get(1).entityIds())
            .containsExactly(atlas.id(), graphStore.id(), team.id());
        assertThat(result.paths().get(1).supportingChunkIds())
            .containsExactly("chunk-1", "chunk-2");
    }

    private static QueryContext seedContext(Entity entity, Relation relation) {
        return new QueryContext(
            List.of(new ScoredEntity(entity.id(), entity, 0.95d)),
            List.of(new ScoredRelation(relation.id(), relation, 0.85d)),
            List.<ScoredChunk>of(),
            ""
        );
    }

    private static Entity entity(String id, String name, List<String> sourceChunkIds) {
        return new Entity(id, name, "Component", "", List.of(), sourceChunkIds);
    }

    private static Relation relation(
        String id,
        String sourceEntityId,
        String targetEntityId,
        String type,
        List<String> sourceChunkIds
    ) {
        return new Relation(id, sourceEntityId, targetEntityId, type, "", 1.0d, sourceChunkIds);
    }

    private static final class FakeGraphStore implements GraphStore {
        private final Map<String, EntityRecord> entitiesById = new LinkedHashMap<>();
        private final Map<String, RelationRecord> relationsById = new LinkedHashMap<>();

        private FakeGraphStore(List<Entity> entities, List<Relation> relations) {
            for (var entity : entities) {
                entitiesById.put(entity.id(), new EntityRecord(
                    entity.id(),
                    entity.name(),
                    entity.type(),
                    entity.description(),
                    entity.aliases(),
                    entity.sourceChunkIds()
                ));
            }
            for (var relation : relations) {
                relationsById.put(relation.id(), new RelationRecord(
                    relation.id(),
                    relation.sourceEntityId(),
                    relation.targetEntityId(),
                    relation.type(),
                    relation.description(),
                    relation.weight(),
                    relation.sourceChunkIds()
                ));
            }
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return Optional.ofNullable(entitiesById.get(entityId));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return Optional.ofNullable(relationsById.get(relationId));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return List.copyOf(entitiesById.values());
        }

        @Override
        public List<RelationRecord> allRelations() {
            return List.copyOf(relationsById.values());
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return relationsById.values().stream()
                .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                .toList();
        }
    }
}
