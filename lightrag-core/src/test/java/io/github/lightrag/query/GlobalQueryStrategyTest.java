package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.indexing.ParentChildChunkBuilder;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:bob", "entity:carol");
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
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:bob", "entity:carol");
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
            .containsExactly("relation:entity:alice|works_with|entity:bob");
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
            "entity:a",
            "entity:b",
            "rel",
            "Single level relation",
            0.8d,
            List.of("chunk-plain")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "entity:a",
            "A",
            "type",
            "A desc",
            List.of(),
            List.of("chunk-plain")
        ));
        storage.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
            "entity:b",
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
            new VectorStore.VectorMatch("relation:entity:bob|reports_to|entity:carol", 1.0d)
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
            .containsExactly("relation:entity:bob|reports_to|entity:carol");
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
        public io.github.lightrag.storage.SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
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
