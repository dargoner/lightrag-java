package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.Relation;
import io.github.lightragjava.types.ScoredChunk;
import io.github.lightragjava.types.ScoredEntity;
import io.github.lightragjava.types.ScoredRelation;
import io.github.lightragjava.types.reasoning.PathRetrievalResult;
import io.github.lightragjava.types.reasoning.ReasoningPath;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiHopQueryStrategyTest {
    @Test
    void assemblesHopStructuredContextAndKeepsFallbackChunks() {
        var atlas = new Entity("entity:atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("entity:graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1", "chunk-2"));
        var team = new Entity("entity:team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-2"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "Atlas relies on GraphStore as its dependency service.", 0.9d, List.of("chunk-1"));
        var ownedBy = new Relation("relation:2", graphStore.id(), team.id(), "owned_by", "GraphStore is maintained by the knowledge graph team.", 1.0d, List.of("chunk-2"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(new ScoredRelation(dependsOn.id(), dependsOn, 0.88d)),
            List.of(scoredChunk("chunk-1", "Atlas 组件依赖 GraphStore 服务。")),
            ""
        );
        var retriever = new StaticSeedContextRetriever(seedContext);
        var pathRetriever = new StaticPathRetriever(new PathRetrievalResult(
            seedContext.matchedEntities(),
            List.of(
                new ScoredRelation(dependsOn.id(), dependsOn, 0.88d),
                new ScoredRelation(ownedBy.id(), ownedBy, 0.84d)
            ),
            List.of(new ReasoningPath(
                List.of(atlas.id(), graphStore.id(), team.id()),
                List.of(dependsOn.id(), ownedBy.id()),
                List.of("chunk-1", "chunk-2"),
                2,
                0.0d
            ))
        ));
        var scorer = new DefaultPathScorer();
        var graphStoreDb = new FakeGraphStore(List.of(atlas, graphStore, team), List.of(dependsOn, ownedBy));
        var chunkStore = new FakeChunkStore(Map.of(
            "chunk-1", chunkRecord("chunk-1", "Atlas 组件依赖 GraphStore 服务。"),
            "chunk-2", chunkRecord("chunk-2", "GraphStore 服务由知识图谱组维护。")
        ));
        var strategy = new MultiHopQueryStrategy(
            retriever,
            pathRetriever,
            scorer,
            new ReasoningContextAssembler(graphStoreDb, chunkStore)
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .pathTopK(2)
            .build());

        assertThat(context.assembledContext())
            .contains("Reasoning Path 1")
            .contains("Hop 1")
            .contains("Hop 2")
            .contains("Atlas --depends_on--> GraphStore")
            .contains("GraphStore --owned_by--> KnowledgeGraphTeam")
            .contains("Relation detail: Atlas relies on GraphStore as its dependency service.")
            .contains("Relation detail: GraphStore is maintained by the knowledge graph team.")
            .contains("Evidence [chunk-1]: Atlas 组件依赖 GraphStore 服务。")
            .contains("Evidence [chunk-2]: GraphStore 服务由知识图谱组维护。");
        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void prioritizesReasoningPathChunksAheadOfFallbackChunks() {
        var atlas = new Entity("entity:atlas", "Atlas", "Component", "", List.of(), List.of("chunk-2"));
        var graphStore = new Entity("entity:graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-2"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 0.9d, List.of("chunk-2"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(),
            List.of(scoredChunk("chunk-fallback", "Unrelated fallback chunk.")),
            ""
        );
        var retriever = new StaticSeedContextRetriever(seedContext);
        var pathRetriever = new StaticPathRetriever(new PathRetrievalResult(
            seedContext.matchedEntities(),
            List.of(new ScoredRelation(dependsOn.id(), dependsOn, 0.88d)),
            List.of(new ReasoningPath(
                List.of(atlas.id(), graphStore.id()),
                List.of(dependsOn.id()),
                List.of("chunk-2"),
                1,
                0.0d
            ))
        ));
        var strategy = new MultiHopQueryStrategy(
            retriever,
            pathRetriever,
            new DefaultPathScorer(),
            new ReasoningContextAssembler(
                new FakeGraphStore(List.of(atlas, graphStore), List.of(dependsOn)),
                new FakeChunkStore(Map.of(
                    "chunk-fallback", chunkRecord("chunk-fallback", "Unrelated fallback chunk."),
                    "chunk-2", chunkRecord("chunk-2", "Atlas 组件依赖 GraphStore 服务。")
                ))
            )
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响 GraphStore？")
            .pathTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-2", "chunk-fallback");
    }

    private static ScoredChunk scoredChunk(String chunkId, String text) {
        return new ScoredChunk(chunkId, new Chunk(chunkId, "doc-1", text, 4, 0, Map.of()), 0.9d);
    }

    private static ChunkStore.ChunkRecord chunkRecord(String chunkId, String text) {
        return new ChunkStore.ChunkRecord(chunkId, "doc-1", text, 4, 0, Map.of());
    }

    private record StaticSeedContextRetriever(QueryContext seedContext) implements SeedContextRetriever {
        @Override
        public QueryContext retrieve(QueryRequest request) {
            return seedContext;
        }
    }

    private record StaticPathRetriever(PathRetrievalResult result) implements PathRetriever {
        @Override
        public PathRetrievalResult retrieve(QueryRequest request, QueryContext seedContext) {
            return result;
        }
    }

    private static final class FakeChunkStore implements ChunkStore {
        private final Map<String, ChunkRecord> records = new LinkedHashMap<>();

        private FakeChunkStore(Map<String, ChunkRecord> records) {
            this.records.putAll(records);
        }

        @Override
        public void save(ChunkRecord chunk) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            return Optional.ofNullable(records.get(chunkId));
        }

        @Override
        public List<ChunkRecord> list() {
            return List.copyOf(records.values());
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return records.values().stream().filter(record -> record.documentId().equals(documentId)).toList();
        }
    }

    private static final class FakeGraphStore implements GraphStore {
        private final Map<String, EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, RelationRecord> relations = new LinkedHashMap<>();

        private FakeGraphStore(List<Entity> entities, List<Relation> relations) {
            for (var entity : entities) {
                this.entities.put(entity.id(), new EntityRecord(
                    entity.id(),
                    entity.name(),
                    entity.type(),
                    entity.description(),
                    entity.aliases(),
                    entity.sourceChunkIds()
                ));
            }
            for (var relation : relations) {
                this.relations.put(relation.id(), new RelationRecord(
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
            return Optional.ofNullable(entities.get(entityId));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return Optional.ofNullable(relations.get(relationId));
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
            return relations.values().stream()
                .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                .toList();
        }
    }
}
