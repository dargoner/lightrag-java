package io.github.lightrag.query;

import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MixQueryStrategyTest {
    @Test
    void mixMergesHybridChunksWithDirectChunkRetrieval() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of("mix question", List.of(1.0d, 0.0d)));
        var contextAssembler = new ContextAssembler();
        var hybrid = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );
        var strategy = new MixQueryStrategy(embeddings, storage, hybrid, contextAssembler);

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix question")
            .mode(QueryMode.MIX)
            .topK(1)
            .chunkTopK(2)
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void mixUsesKeywordOverridesForGraphRetrievalButRawQueryForDirectChunks() {
        var storage = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(storage);
        LocalQueryStrategyTest.seedVectors(storage);
        var embeddings = new FakeEmbeddingModel(Map.of(
            "direct chunk question", List.of(0.0d, 1.0d),
            "alice, focus", List.of(1.0d, 0.0d)
        ));
        var contextAssembler = new ContextAssembler();
        var hybrid = new HybridQueryStrategy(
            new LocalQueryStrategy(embeddings, storage, contextAssembler),
            new GlobalQueryStrategy(embeddings, storage, contextAssembler),
            contextAssembler
        );
        var strategy = new MixQueryStrategy(embeddings, storage, hybrid, contextAssembler);

        var context = strategy.retrieve(QueryRequest.builder()
            .query("direct chunk question")
            .mode(QueryMode.MIX)
            .topK(1)
            .chunkTopK(3)
            .llKeywords(List.of("alice", "focus"))
            .build());

        assertThat(context.matchedEntities())
            .extracting(match -> match.entityId())
            .containsExactly("entity:alice", "entity:bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly("relation:entity:alice|works_with|entity:bob");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
    }

    @Test
    void mixUsesHybridVectorSearchRequestForDirectChunkRetrievalWhenSupported() {
        var delegate = InMemoryStorageProvider.create();
        LocalQueryStrategyTest.seedGraph(delegate);
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("chunk-3", 0.9d)
        ));
        var storage = new TestStorageProvider(delegate, vectorStore);
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("direct chunk question", List.of(0.0d, 1.0d))),
            storage,
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(new ScoredChunk(
                    "chunk-1",
                    new Chunk("chunk-1", "doc-1", "Alice works with Bob", 4, 0, Map.of()),
                    1.0d
                )),
                ""
            ),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("direct chunk question")
            .mode(QueryMode.MIX)
            .topK(1)
            .chunkTopK(3)
            .llKeywords(List.of("alice"))
            .hlKeywords(List.of("org"))
            .build());

        assertThat(vectorStore.recordedRequest).isNotNull();
        assertThat(vectorStore.recordedRequest.mode()).isEqualTo(HybridVectorStore.SearchMode.HYBRID);
        assertThat(vectorStore.recordedRequest.queryVector()).containsExactly(0.0d, 1.0d);
        assertThat(vectorStore.recordedRequest.queryText()).isEqualTo("direct chunk question");
        assertThat(vectorStore.recordedRequest.keywords()).containsExactly("alice", "org");
        assertThat(context.matchedChunks())
            .extracting(match -> match.chunkId())
            .containsExactly("chunk-1", "chunk-3");
    }

    @Test
    void mixAppliesFinalMetadataSafeguardAfterHybridAndDirectChunkMerge() {
        var delegate = InMemoryStorageProvider.create();
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-2",
            "doc-2",
            "Direct chunk in Shanghai",
            4,
            0,
            Map.of("region", "shanghai")
        ));
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-3",
            "doc-3",
            "Direct chunk in Beijing",
            4,
            0,
            Map.of("region", "beijing")
        ));
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("chunk-2", 0.92d),
            new VectorStore.VectorMatch("chunk-3", 0.91d)
        ));
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix metadata question", List.of(1.0d, 0.0d))),
            new TestStorageProvider(delegate, vectorStore),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(
                    new ScoredChunk(
                        "chunk-1",
                        new Chunk("chunk-1", "doc-1", "Hybrid chunk in Shanghai", 4, 0, Map.of("region", "shanghai")),
                        0.95d
                    ),
                    new ScoredChunk(
                        "chunk-4",
                        new Chunk("chunk-4", "doc-4", "Hybrid chunk in Beijing", 4, 0, Map.of("region", "beijing")),
                        0.99d
                    )
                ),
                ""
            ),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix metadata question")
            .mode(QueryMode.MIX)
            .chunkTopK(5)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void mixFiltersBeforeApplyingChunkTopKSoDirectBackfillStillWorks() {
        var delegate = InMemoryStorageProvider.create();
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-3",
            "doc-3",
            "Allowed lower score",
            4,
            0,
            Map.of("region", "shanghai")
        ));
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("chunk-3", 0.90d)
        ));
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix backfill question", List.of(1.0d, 0.0d))),
            new TestStorageProvider(delegate, vectorStore),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(
                    new ScoredChunk(
                        "chunk-1",
                        new Chunk("chunk-1", "doc-1", "Filtered high score", 4, 0, Map.of("region", "beijing")),
                        0.99d
                    ),
                    new ScoredChunk(
                        "chunk-2",
                        new Chunk("chunk-2", "doc-2", "Allowed second score", 4, 0, Map.of("region", "shanghai")),
                        0.95d
                    )
                ),
                ""
            ),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix backfill question")
            .mode(QueryMode.MIX)
            .chunkTopK(2)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-2", "chunk-3");
    }

    @Test
    void mixOverfetchesDirectChunkMatchesWhenMetadataFilteringRemovesInitialVectorHits() {
        var delegate = InMemoryStorageProvider.create();
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-2",
            "doc-2",
            "Filtered direct hit one",
            4,
            0,
            Map.of("region", "beijing")
        ));
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-3",
            "doc-3",
            "Filtered direct hit two",
            4,
            0,
            Map.of("region", "beijing")
        ));
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-4",
            "doc-4",
            "Allowed lower ranked direct hit",
            4,
            0,
            Map.of("region", "shanghai")
        ));
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("chunk-2", 0.94d),
            new VectorStore.VectorMatch("chunk-3", 0.93d),
            new VectorStore.VectorMatch("chunk-4", 0.92d)
        ));
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix metadata overfetch question", List.of(1.0d, 0.0d))),
            new TestStorageProvider(delegate, vectorStore),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(new ScoredChunk(
                    "chunk-1",
                    new Chunk("chunk-1", "doc-1", "Hybrid allowed hit", 4, 0, Map.of("region", "shanghai")),
                    0.95d
                )),
                ""
            ),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix metadata overfetch question")
            .mode(QueryMode.MIX)
            .chunkTopK(2)
            .metadataFilters(Map.of("region", List.of("shanghai")))
            .build());

        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1", "chunk-4");
        assertThat(vectorStore.recordedRequest).isNotNull();
        assertThat(vectorStore.recordedRequest.topK()).isGreaterThan(2);
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
            return matches.stream().limit(request.topK()).toList();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }
}
