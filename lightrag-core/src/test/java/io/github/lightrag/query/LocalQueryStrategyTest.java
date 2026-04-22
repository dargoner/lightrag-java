package io.github.lightrag.query;

import io.github.lightrag.api.MetadataCondition;
import io.github.lightrag.api.MetadataOperator;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.indexing.ParentChildChunkBuilder;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.lightrag.support.RelationIds.relationId;

class LocalQueryStrategyTest {
    @Test
    void localUsesEntitySimilarityAndOneHopNeighbors() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("alice", "bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("alice", "bob"));
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
        assertThat(context.assembledContext())
            .contains("Entities:")
            .contains("Alice")
            .contains("Relations:")
            .contains("works_with")
            .contains("Chunks:")
            .contains("chunk-1");
    }

    @Test
    void localTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    @Test
    void localUsesLlKeywordsInsteadOfRawQueryWhenProvided() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(0.0d, 1.0d),
            "alice, focus", List.of(1.0d, 0.0d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .llKeywords(List.of(" ", "alice", "focus", ""))
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("alice", "bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("alice", "bob"));
    }

    @Test
    void localTrimsEntitiesToMaxEntityTokens() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .maxEntityTokens(6)
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("alice");
    }

    @Test
    void localAppliesMetadataConditionsToCollectedChunks() {
        var storage = InMemoryStorageProvider.create();
        seedGraph(storage);
        seedVectors(storage);
        var strategy = new LocalQueryStrategy(new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(3)
            .metadataConditions(List.of(new MetadataCondition("score", MetadataOperator.GTE, "90")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
        assertThat(context.matchedChunks())
            .allSatisfy(match -> assertThat(match.chunk().metadata()).containsEntry("score", "95"));
    }

    @Test
    void localReturnsParentContextForChildChunkEntityReferences() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-parent",
            "doc-1",
            "Parent context with full explanation",
            5,
            0,
            Map.of(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_PARENT)
        ));
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-parent#child:0",
            "doc-1",
            "full explanation",
            2,
            1,
            Map.of(
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent"
            )
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "alpha",
            "Alpha",
            "concept",
            "Parent-child test",
            List.of(),
            List.of("chunk-parent#child:0")
        ));
        storage.vectorStore().saveAll("entities", List.of(
            new VectorStore.VectorRecord("alpha", List.of(1.0d, 0.0d))
        ));

        var strategy = new LocalQueryStrategy(
            new FakeEmbeddingModel(Map.of("alpha question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alpha question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-parent");
        assertThat(context.matchedChunks().get(0).chunk().text()).isEqualTo("Parent context with full explanation");
    }

    @Test
    void localUsesHybridVectorSearchRequestWhenStoreSupportsIt() {
        var delegate = InMemoryStorageProvider.create();
        seedGraph(delegate);
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("alice", 1.0d)
        ));
        var storage = new TestStorageProvider(delegate, vectorStore);
        var strategy = new LocalQueryStrategy(
            new FakeEmbeddingModel(Map.of("alice, focus", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .llKeywords(List.of("alice", "focus"))
            .build());

        assertThat(vectorStore.recordedRequest).isNotNull();
        assertThat(vectorStore.recordedRequest.mode()).isEqualTo(HybridVectorStore.SearchMode.HYBRID);
        assertThat(vectorStore.recordedRequest.queryVector()).containsExactly(1.0d, 0.0d);
        assertThat(vectorStore.recordedRequest.queryText()).isEqualTo("ambiguous question");
        assertThat(vectorStore.recordedRequest.keywords()).containsExactly("alice", "focus");
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("alice", "bob");
    }

    @Test
    void localBatchesEntityAndRelationLoads() {
        var delegate = InMemoryStorageProvider.create();
        seedGraph(delegate);
        seedVectors(delegate);
        var graphStore = new RecordingGraphStore(delegate.graphStore());
        var storage = new GraphStoreDecoratingStorageProvider(delegate, graphStore);
        var strategy = new LocalQueryStrategy(
            new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(graphStore.loadEntitiesCalls).isEqualTo(1);
        assertThat(graphStore.loadRelationsCalls).isEqualTo(1);
        assertThat(graphStore.batchFindRelationsCalls).isEqualTo(1);
        assertThat(graphStore.loadEntityCalls).isZero();
        assertThat(graphStore.loadRelationCalls).isZero();
        assertThat(graphStore.findRelationsCalls).isZero();
    }

    @Test
    void localBatchesChunkLoads() {
        var delegate = InMemoryStorageProvider.create();
        seedGraph(delegate);
        seedVectors(delegate);
        var chunkStore = new RecordingChunkStore(delegate.chunkStore());
        var storage = new ChunkStoreDecoratingStorageProvider(delegate, chunkStore);
        var strategy = new LocalQueryStrategy(
            new FakeEmbeddingModel(Map.of("alice question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        strategy.retrieve(QueryRequest.builder()
            .query("alice question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(chunkStore.batchLoadCalls).isEqualTo(1);
        assertThat(chunkStore.loadCalls).isZero();
    }

    @Test
    void localDropsExpandedParentWhenParentMetadataDoesNotMatch() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-parent",
            "doc-1",
            "Parent context",
            4,
            0,
            Map.of("region", "beijing")
        ));
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-parent#child:0",
            "doc-1",
            "Child context",
            2,
            1,
            Map.of(
                "region", "shanghai",
                ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, ParentChildChunkBuilder.CHUNK_LEVEL_CHILD,
                ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, "chunk-parent"
            )
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "alpha",
            "Alpha",
            "concept",
            "Metadata filter test",
            List.of(),
            List.of("chunk-parent#child:0")
        ));
        storage.vectorStore().saveAll("entities", List.of(
            new VectorStore.VectorRecord("alpha", List.of(1.0d, 0.0d))
        ));

        var strategy = new LocalQueryStrategy(
            new FakeEmbeddingModel(Map.of("alpha metadata question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alpha metadata question")
            .mode(QueryMode.LOCAL)
            .topK(1)
            .chunkTopK(1)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("alpha");
        assertThat(context.matchedChunks()).isEmpty();
    }

    static void seedGraph(InMemoryStorageProvider storage) {
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-1",
            "doc-1",
            "Alice works with Bob",
            4,
            0,
            Map.of("region", "shanghai", "score", "95")
        ));
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-2",
            "doc-1",
            "Bob supports Alice",
            4,
            1,
            Map.of("region", "beijing", "score", "82")
        ));
        storage.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-3",
            "doc-2",
            "Bob reports to Carol",
            4,
            0,
            Map.of("region", "shanghai", "score", "88")
        ));

        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "alice",
            "Alice",
            "person",
            "Researcher",
            List.of(),
            List.of("chunk-1")
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "bob",
            "Bob",
            "person",
            "Engineer",
            List.of("Robert"),
            List.of("chunk-2", "chunk-3")
        ));
        storage.graphStore().saveEntity(new GraphStore.EntityRecord(
            "carol",
            "Carol",
            "person",
            "Manager",
            List.of(),
            List.of("chunk-3")
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            relationId("alice", "bob"),
            "alice",
            "bob",
            "works_with",
            "Alice collaborates with Bob",
            0.8d,
            List.of("chunk-1", "chunk-2")
        ));
        storage.graphStore().saveRelation(new GraphStore.RelationRecord(
            relationId("bob", "carol"),
            "bob",
            "carol",
            "reports_to",
            "Bob reports to Carol",
            0.6d,
            List.of("chunk-3")
        ));
    }

    static void seedVectors(InMemoryStorageProvider storage) {
        storage.vectorStore().saveAll("chunks", List.of(
            new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord("chunk-2", List.of(0.7d, 0.3d)),
            new VectorStore.VectorRecord("chunk-3", List.of(0.0d, 1.0d))
        ));
        storage.vectorStore().saveAll("entities", List.of(
            new VectorStore.VectorRecord("alice", List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord("bob", List.of(0.6d, 0.4d)),
            new VectorStore.VectorRecord("carol", List.of(0.0d, 1.0d))
        ));
        storage.vectorStore().saveAll("relations", List.of(
            new VectorStore.VectorRecord(relationId("alice", "bob"), List.of(1.0d, 0.0d)),
            new VectorStore.VectorRecord(relationId("bob", "carol"), List.of(0.0d, 1.0d))
        ));
    }

    private record FakeEmbeddingModel(Map<String, List<Double>> vectorsByText) implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> vectorsByText.getOrDefault(text, List.of(0.0d, 0.0d)))
                .toList();
        }
    }

    private static final class TestStorageProvider implements StorageProvider {
        private final InMemoryStorageProvider delegate;
        private final HybridVectorStore vectorStore;

        private TestStorageProvider(InMemoryStorageProvider delegate, HybridVectorStore vectorStore) {
            this.delegate = delegate;
            this.vectorStore = vectorStore;
        }

        @Override
        public io.github.lightrag.storage.DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public io.github.lightrag.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public io.github.lightrag.storage.SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }
    }

    private static final class GraphStoreDecoratingStorageProvider implements StorageProvider {
        private final InMemoryStorageProvider delegate;
        private final GraphStore graphStore;

        private GraphStoreDecoratingStorageProvider(InMemoryStorageProvider delegate, GraphStore graphStore) {
            this.delegate = delegate;
            this.graphStore = graphStore;
        }

        @Override
        public io.github.lightrag.storage.DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return graphStore;
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public io.github.lightrag.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public io.github.lightrag.storage.SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }
    }

    private static final class ChunkStoreDecoratingStorageProvider implements StorageProvider {
        private final InMemoryStorageProvider delegate;
        private final ChunkStore chunkStore;

        private ChunkStoreDecoratingStorageProvider(InMemoryStorageProvider delegate, ChunkStore chunkStore) {
            this.delegate = delegate;
            this.chunkStore = chunkStore;
        }

        @Override
        public io.github.lightrag.storage.DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return chunkStore;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public io.github.lightrag.storage.DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public io.github.lightrag.storage.TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public io.github.lightrag.storage.SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }
    }

    private static final class RecordingGraphStore implements GraphStore {
        private final GraphStore delegate;
        private int loadEntityCalls;
        private int loadRelationCalls;
        private int loadEntitiesCalls;
        private int loadRelationsCalls;
        private int findRelationsCalls;
        private int batchFindRelationsCalls;

        private RecordingGraphStore(GraphStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            delegate.saveEntity(entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            delegate.saveRelation(relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            loadEntityCalls++;
            return delegate.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            loadRelationCalls++;
            return delegate.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> loadEntities(List<String> entityIds) {
            loadEntitiesCalls++;
            return delegate.loadEntities(entityIds);
        }

        @Override
        public List<RelationRecord> loadRelations(List<String> relationIds) {
            loadRelationsCalls++;
            return delegate.loadRelations(relationIds);
        }

        @Override
        public Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
            batchFindRelationsCalls++;
            return delegate.findRelations(entityIds);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            findRelationsCalls++;
            return delegate.findRelations(entityId);
        }
    }

    private static final class RecordingChunkStore implements ChunkStore {
        private final ChunkStore delegate;
        private int loadCalls;
        private int batchLoadCalls;

        private RecordingChunkStore(ChunkStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ChunkRecord chunk) {
            delegate.save(chunk);
        }

        @Override
        public java.util.Optional<ChunkRecord> load(String chunkId) {
            loadCalls++;
            return delegate.load(chunkId);
        }

        @Override
        public Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
            batchLoadCalls++;
            return delegate.loadAll(chunkIds);
        }

        @Override
        public List<ChunkRecord> list() {
            return delegate.list();
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return delegate.listByDocument(documentId);
        }
    }

    private static final class RecordingHybridVectorStore implements HybridVectorStore {
        private final List<VectorMatch> matches;
        private SearchRequest recordedRequest;

        private RecordingHybridVectorStore(List<VectorMatch> matches) {
            this.matches = matches;
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return List.of();
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            this.recordedRequest = request;
            return matches;
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }
}
