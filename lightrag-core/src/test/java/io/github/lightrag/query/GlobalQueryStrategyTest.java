package io.github.lightrag.query;

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

class GlobalQueryStrategyTest {
    @Test
    void globalUsesRelationSimilarityAndEndpointEntities() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of("org question", List.of(0.0d, 1.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("org question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("bob", "carol"));
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("bob", "carol");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-3");
        assertThat(context.assembledContext())
            .contains("Relations:")
            .contains("reports_to")
            .contains("Entities:")
            .contains("Carol");
    }

    @Test
    void globalTrimsChunksToChunkTopK() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of("alice theme", List.of(1.0d, 0.0d))), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("alice theme")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1");
    }

    @Test
    void globalUsesHlKeywordsInsteadOfRawQueryWhenProvided() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "ambiguous question", List.of(1.0d, 0.0d),
            "org, focus", List.of(0.0d, 1.0d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .hlKeywords(List.of("org", "focus"))
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("bob", "carol"));
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("bob", "carol");
    }

    @Test
    void globalTrimsRelationsToMaxRelationTokens() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "broad org question", List.of(0.8d, 0.6d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("broad org question")
            .mode(QueryMode.GLOBAL)
            .topK(2)
            .chunkTopK(2)
            .maxRelationTokens(6)
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("alice", "bob"));
    }

    @Test
    void globalAppliesMetadataFiltersWithoutChangingRelationRetrieval() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var strategy = new GlobalQueryStrategy(new FakeEmbeddingModel(Map.of(
            "broad org question", List.of(0.8d, 0.6d)
        )), storage, new ContextAssembler());

        var context = strategy.retrieve(QueryRequest.builder()
            .query("broad org question")
            .mode(QueryMode.GLOBAL)
            .topK(2)
            .chunkTopK(3)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(
                relationId("alice", "bob"),
                relationId("bob", "carol")
            );
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-3");
        assertThat(context.matchedChunks())
            .allSatisfy(match -> assertThat(match.chunk().metadata()).containsEntry("region", "shanghai"));
    }

    @Test
    void fallsBackToSingleLevelChunksBeforeV3ConfigIsEnabled() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-plain",
            "doc-1",
            "Single level chunk",
            3,
            0,
            Map.of()
        ));
        storage.graphStore().saveRelation(new io.github.lightrag.storage.GraphStore.RelationRecord(
            "relation:plain",
            "a",
            "b",
            "rel",
            "Single level relation",
            0.8d,
            List.of("chunk-plain")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "a",
            "A",
            "type",
            "A desc",
            List.of(),
            List.of("chunk-plain")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "b",
            "B",
            "type",
            "B desc",
            List.of(),
            List.of("chunk-plain")
        ));
        storage.vectorStore().saveAll("relations", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("relation:plain", List.of(1.0d, 0.0d))
        ));

        var strategy = new GlobalQueryStrategy(
            new FakeEmbeddingModel(Map.of("plain question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("plain question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(1)
            .build());

        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-plain");
        assertThat(context.matchedChunks().get(0).chunk().text()).isEqualTo("Single level chunk");
    }

    @Test
    void globalUsesHybridVectorSearchRequestWhenStoreSupportsIt() {
        var delegate = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(delegate);
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch(relationId("bob", "carol"), 1.0d)
        ));
        var storage = new TestStorageProvider(delegate, vectorStore);
        var strategy = new GlobalQueryStrategy(
            new FakeEmbeddingModel(Map.of("org, focus", List.of(0.0d, 1.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("ambiguous question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .hlKeywords(List.of("org", "focus"))
            .build());

        assertThat(vectorStore.recordedRequest).isNotNull();
        assertThat(vectorStore.recordedRequest.mode()).isEqualTo(HybridVectorStore.SearchMode.HYBRID);
        assertThat(vectorStore.recordedRequest.queryVector()).containsExactly(0.0d, 1.0d);
        assertThat(vectorStore.recordedRequest.queryText()).isEqualTo("ambiguous question");
        assertThat(vectorStore.recordedRequest.keywords()).containsExactly("org", "focus");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("bob", "carol"));
    }

    @Test
    void globalBatchesEntityAndRelationLoads() {
        var delegate = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(delegate);
        LocalQueryStrategyTest.seedVectors(delegate);
        var graphStore = new RecordingGraphStore(delegate.graphStore());
        var storage = new GraphStoreDecoratingStorageProvider(delegate, graphStore);
        var strategy = new GlobalQueryStrategy(
            new FakeEmbeddingModel(Map.of("org question", List.of(0.0d, 1.0d))),
            storage,
            new ContextAssembler()
        );

        strategy.retrieve(QueryRequest.builder()
            .query("org question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(graphStore.loadEntitiesCalls).isEqualTo(1);
        assertThat(graphStore.loadRelationsCalls).isEqualTo(1);
        assertThat(graphStore.loadEntityCalls).isZero();
        assertThat(graphStore.loadRelationCalls).isZero();
    }

    @Test
    void globalBatchesChunkLoads() {
        var delegate = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(delegate);
        LocalQueryStrategyTest.seedVectors(delegate);
        var chunkStore = new RecordingChunkStore(delegate.chunkStore());
        var storage = new ChunkStoreDecoratingStorageProvider(delegate, chunkStore);
        var strategy = new GlobalQueryStrategy(
            new FakeEmbeddingModel(Map.of("org question", List.of(0.0d, 1.0d))),
            storage,
            new ContextAssembler()
        );

        strategy.retrieve(QueryRequest.builder()
            .query("org question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(chunkStore.batchLoadCalls).isEqualTo(1);
        assertThat(chunkStore.loadCalls).isZero();
    }

    @Test
    void globalDropsExpandedParentWhenParentMetadataDoesNotMatch() {
        var storage = InMemoryStorageProvider.create();
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-parent",
            "doc-1",
            "Parent context",
            4,
            0,
            Map.of("region", "beijing")
        ));
        storage.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
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
        storage.graphStore().saveRelation(new io.github.lightrag.storage.GraphStore.RelationRecord(
            "relation:alpha",
            "a",
            "b",
            "rel",
            "Metadata filter test",
            0.8d,
            List.of("chunk-parent#child:0")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "a",
            "A",
            "type",
            "A desc",
            List.of(),
            List.of("chunk-parent#child:0")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "b",
            "B",
            "type",
            "B desc",
            List.of(),
            List.of("chunk-parent#child:0")
        ));
        storage.vectorStore().saveAll("relations", List.of(
            new io.github.lightrag.storage.VectorStore.VectorRecord("relation:alpha", List.of(1.0d, 0.0d))
        ));

        var strategy = new GlobalQueryStrategy(
            new FakeEmbeddingModel(Map.of("global metadata question", List.of(1.0d, 0.0d))),
            storage,
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("global metadata question")
            .mode(QueryMode.GLOBAL)
            .topK(1)
            .chunkTopK(1)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:alpha");
        assertThat(context.matchedChunks()).isEmpty();
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
        public io.github.lightrag.storage.ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public io.github.lightrag.storage.GraphStore graphStore() {
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
        public List<EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
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
