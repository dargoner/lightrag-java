package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import io.github.lightrag.types.reasoning.RelationEndpoint;
import io.github.lightrag.types.reasoning.ReasoningPath;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MultiHopQueryStrategyTest {
    @Test
    void assemblesHopStructuredContextAndKeepsFallbackChunks() {
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1", "chunk-2"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-2"));
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
                List.of(
                    new RelationEndpoint(atlas.id(), graphStore.id()),
                    new RelationEndpoint(graphStore.id(), team.id())
                ),
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
            .contains("Atlas -> GraphStore | keywords: depends_on")
            .contains("GraphStore -> KnowledgeGraphTeam | keywords: owned_by")
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
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-2"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-2"));
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
                List.of(new RelationEndpoint(atlas.id(), graphStore.id())),
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

    @Test
    void reservesAtLeastOneEvidenceChunkPerHopBeforeExtraPathChunks() {
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-1", "chunk-2"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-1", "chunk-2", "chunk-3"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-3"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 0.9d, List.of("chunk-1", "chunk-2"));
        var ownedBy = new Relation("relation:2", graphStore.id(), team.id(), "owned_by", "", 1.0d, List.of("chunk-3"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(),
            List.of(scoredChunk("chunk-fallback", "Fallback chunk.")),
            ""
        );
        var strategy = new MultiHopQueryStrategy(
            new StaticSeedContextRetriever(seedContext),
            new StaticPathRetriever(new PathRetrievalResult(
                seedContext.matchedEntities(),
                List.of(
                    new ScoredRelation(dependsOn.id(), dependsOn, 0.88d),
                    new ScoredRelation(ownedBy.id(), ownedBy, 0.84d)
                ),
                List.of(new ReasoningPath(
                    List.of(atlas.id(), graphStore.id(), team.id()),
                    List.of(
                        new RelationEndpoint(atlas.id(), graphStore.id()),
                        new RelationEndpoint(graphStore.id(), team.id())
                    ),
                    List.of("chunk-1", "chunk-2", "chunk-3"),
                    2,
                    0.0d
                ))
            )),
            new DefaultPathScorer(),
            new ReasoningContextAssembler(
                new FakeGraphStore(List.of(atlas, graphStore, team), List.of(dependsOn, ownedBy)),
                new FakeChunkStore(Map.of(
                    "chunk-1", chunkRecord("chunk-1", "Hop one primary evidence."),
                    "chunk-2", chunkRecord("chunk-2", "Hop one extra evidence."),
                    "chunk-3", chunkRecord("chunk-3", "Hop two primary evidence."),
                    "chunk-fallback", chunkRecord("chunk-fallback", "Fallback chunk.")
                ))
            )
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .pathTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1", "chunk-3", "chunk-2", "chunk-fallback");
    }

    @Test
    void fallsBackToSeedContextWhenRerankedPathsAreTooWeak() {
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-seed"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-hop"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 0.3d, List.of("chunk-hop"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(),
            List.of(scoredChunk("chunk-seed", "Seed fallback chunk.")),
            ""
        );
        var strategy = new MultiHopQueryStrategy(
            new StaticSeedContextRetriever(seedContext),
            new StaticPathRetriever(new PathRetrievalResult(
                seedContext.matchedEntities(),
                List.of(new ScoredRelation(dependsOn.id(), dependsOn, 0.20d)),
                List.of(new ReasoningPath(
                    List.of(atlas.id(), graphStore.id()),
                    List.of(new RelationEndpoint(atlas.id(), graphStore.id())),
                    List.of(),
                    1,
                    0.0d
                ))
            )),
            (request, retrievalResult) -> List.of(new ReasoningPath(
                List.of(atlas.id(), graphStore.id()),
                List.of(new RelationEndpoint(atlas.id(), graphStore.id())),
                List.of(),
                1,
                0.20d
            )),
            new ReasoningContextAssembler(
                new FakeGraphStore(List.of(atlas, graphStore), List.of(dependsOn)),
                new FakeChunkStore(Map.of(
                    "chunk-seed", chunkRecord("chunk-seed", "Seed fallback chunk."),
                    "chunk-hop", chunkRecord("chunk-hop", "Weak hop evidence.")
                ))
            )
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响 GraphStore？")
            .pathTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-seed");
        assertThat(context.assembledContext()).isBlank();
    }

    @Test
    void clearsAssembledContextWhenMetadataFilteringChangesSupportingChunks() {
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-seed"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-hop-1", "chunk-hop-2"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-hop-2"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 0.9d, List.of("chunk-hop-1"));
        var ownedBy = new Relation("relation:2", graphStore.id(), team.id(), "owned_by", "", 0.85d, List.of("chunk-hop-2"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(),
            List.of(scoredChunk("chunk-seed", "Seed fallback chunk.")),
            ""
        );
        var strategy = new MultiHopQueryStrategy(
            new StaticSeedContextRetriever(seedContext),
            new StaticPathRetriever(new PathRetrievalResult(
                seedContext.matchedEntities(),
                List.of(
                    new ScoredRelation(dependsOn.id(), dependsOn, 0.88d),
                    new ScoredRelation(ownedBy.id(), ownedBy, 0.84d)
                ),
                List.of(new ReasoningPath(
                    List.of(atlas.id(), graphStore.id(), team.id()),
                    List.of(
                        new RelationEndpoint(atlas.id(), graphStore.id()),
                        new RelationEndpoint(graphStore.id(), team.id())
                    ),
                    List.of("chunk-hop-1", "chunk-hop-2"),
                    2,
                    0.0d
                ))
            )),
            new DefaultPathScorer(),
            new ReasoningContextAssembler(
                new FakeGraphStore(List.of(atlas, graphStore, team), List.of(dependsOn, ownedBy)),
                new FakeChunkStore(Map.of(
                    "chunk-seed", chunkRecord("chunk-seed", "Seed fallback chunk.", Map.of("region", "beijing")),
                    "chunk-hop-1", chunkRecord("chunk-hop-1", "Hop one evidence.", Map.of("region", "shanghai")),
                    "chunk-hop-2", chunkRecord("chunk-hop-2", "Hop two evidence.", Map.of("region", "beijing"))
                ))
            )
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .pathTopK(1)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-hop-1");
        assertThat(context.assembledContext()).isBlank();
    }

    @Test
    void clearsAssembledContextWhenMetadataConditionsChangeSupportingChunks() {
        var atlas = new Entity("atlas", "Atlas", "Component", "", List.of(), List.of("chunk-hop-1"));
        var graphStore = new Entity("graphstore", "GraphStore", "Service", "", List.of(), List.of("chunk-hop-1", "chunk-hop-2"));
        var team = new Entity("team", "KnowledgeGraphTeam", "Team", "", List.of(), List.of("chunk-hop-2"));
        var dependsOn = new Relation("relation:1", atlas.id(), graphStore.id(), "depends_on", "", 0.9d, List.of("chunk-hop-1"));
        var ownedBy = new Relation("relation:2", graphStore.id(), team.id(), "owned_by", "", 0.85d, List.of("chunk-hop-2"));
        var seedContext = new QueryContext(
            List.of(new ScoredEntity(atlas.id(), atlas, 0.95d)),
            List.of(),
            List.of(),
            ""
        );
        var strategy = new MultiHopQueryStrategy(
            new StaticSeedContextRetriever(seedContext),
            new StaticPathRetriever(new PathRetrievalResult(
                seedContext.matchedEntities(),
                List.of(
                    new ScoredRelation(dependsOn.id(), dependsOn, 0.88d),
                    new ScoredRelation(ownedBy.id(), ownedBy, 0.84d)
                ),
                List.of(new ReasoningPath(
                    List.of(atlas.id(), graphStore.id(), team.id()),
                    List.of(
                        new RelationEndpoint(atlas.id(), graphStore.id()),
                        new RelationEndpoint(graphStore.id(), team.id())
                    ),
                    List.of("chunk-hop-1", "chunk-hop-2"),
                    2,
                    0.0d
                ))
            )),
            new DefaultPathScorer(),
            new ReasoningContextAssembler(
                new FakeGraphStore(List.of(atlas, graphStore, team), List.of(dependsOn, ownedBy)),
                new FakeChunkStore(Map.of(
                    "chunk-hop-1", chunkRecord("chunk-hop-1", "Hop one evidence.", Map.of("score", "95")),
                    "chunk-hop-2", chunkRecord("chunk-hop-2", "Hop two evidence.", Map.of("score", "70"))
                ))
            )
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("Atlas 通过谁影响知识图谱组？")
            .pathTopK(1)
            .metadataConditions(List.of(
                new io.github.lightrag.api.MetadataCondition(
                    "score",
                    io.github.lightrag.api.MetadataOperator.GTE,
                    "80"
                )
            ))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-hop-1");
        assertThat(context.assembledContext()).isBlank();
    }

    private static ScoredChunk scoredChunk(String chunkId, String text) {
        return new ScoredChunk(chunkId, new Chunk(chunkId, "doc-1", text, 4, 0, Map.of()), 0.9d);
    }

    private static ChunkStore.ChunkRecord chunkRecord(String chunkId, String text) {
        return new ChunkStore.ChunkRecord(chunkId, "doc-1", text, 4, 0, Map.of());
    }

    private static ChunkStore.ChunkRecord chunkRecord(String chunkId, String text, Map<String, String> metadata) {
        return new ChunkStore.ChunkRecord(chunkId, "doc-1", text, 4, 0, metadata);
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
                    relation.srcId(),
                    relation.tgtId(),
                    relation.keywords(),
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
                .filter(relation -> relation.srcId().equals(entityId) || relation.tgtId().equals(entityId))
                .toList();
        }
    }
}
