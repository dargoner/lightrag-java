package io.github.lightrag.query;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.lightrag.support.RelationIds.relationId;
import org.slf4j.LoggerFactory;

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
            .containsExactly("alice", "bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("alice", "bob"));
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
            .containsExactly("alice", "bob");
        assertThat(context.matchedRelations())
            .extracting(match -> match.relationId())
            .containsExactly(relationId("alice", "bob"));
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
    void mixLoadsDirectChunksInBatch() {
        var delegate = InMemoryStorageProvider.create();
        delegate.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-2",
            "doc-2",
            "Batch direct chunk two",
            4,
            0,
            Map.of()
        ));
        delegate.chunkStore().save(new ChunkStore.ChunkRecord(
            "chunk-3",
            "doc-3",
            "Batch direct chunk three",
            4,
            0,
            Map.of()
        ));
        var vectorStore = new RecordingHybridVectorStore(List.of(
            new VectorStore.VectorMatch("chunk-2", 0.92d),
            new VectorStore.VectorMatch("chunk-3", 0.91d)
        ));
        var countingChunkStore = new CountingChunkStore(delegate.chunkStore());
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix batch question", List.of(1.0d, 0.0d))),
            new ChunkStoreOverrideStorageProvider(delegate, vectorStore, countingChunkStore),
            request -> new QueryContext(
                List.of(),
                List.of(),
                List.of(new ScoredChunk(
                    "chunk-1",
                    new Chunk("chunk-1", "doc-1", "Hybrid chunk", 4, 0, Map.of()),
                    1.0d
                )),
                ""
            ),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix batch question")
            .mode(QueryMode.MIX)
            .chunkTopK(3)
            .build());

        assertThat(countingChunkStore.loadCalls()).isZero();
        assertThat(countingChunkStore.loadAllCalls()).isEqualTo(1);
        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1", "chunk-2", "chunk-3");
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

    @Test
    void logsDetailedStageDurationsForMixRetrieval() {
        var logger = (Logger) LoggerFactory.getLogger(MixQueryStrategy.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var delegate = InMemoryStorageProvider.create();
            LocalQueryStrategyTest.seedGraph(delegate);
            LocalQueryStrategyTest.seedVectors(delegate);
            var embeddings = new FakeEmbeddingModel(Map.of("mix question", List.of(1.0d, 0.0d)));
            var contextAssembler = new ContextAssembler();
            var hybrid = new HybridQueryStrategy(
                new LocalQueryStrategy(embeddings, delegate, contextAssembler),
                new GlobalQueryStrategy(embeddings, delegate, contextAssembler),
                contextAssembler
            );
            var strategy = new MixQueryStrategy(embeddings, delegate, hybrid, contextAssembler);

            strategy.retrieve(QueryRequest.builder()
                .query("mix question")
                .mode(QueryMode.MIX)
                .topK(1)
                .chunkTopK(2)
                .build());

            assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> {
                    assertThat(message).contains("LightRAG mix retrieve completed");
                    assertThat(message).contains("hybridMs=");
                    assertThat(message).contains("embedMs=");
                    assertThat(message).contains("chunkVectorSearchMs=");
                    assertThat(message).contains("mergeFilterMs=");
                    assertThat(message).contains("assembleMs=");
                });
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void mixStartsDirectChunkFirstPassConcurrentlyWithHybridRetrieval() {
        var delegate = InMemoryStorageProvider.create();
        delegate.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
            "chunk-1",
            "doc-1",
            "Parallel direct chunk",
            4,
            0,
            Map.of()
        ));
        var barrier = new CyclicBarrier(2);
        var hybridObservedParallelism = new AtomicBoolean(false);
        var chunkObservedParallelism = new AtomicBoolean(false);
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix parallel question", List.of(1.0d, 0.0d))),
            new TestStorageProvider(delegate, new BarrierHybridVectorStore(
                List.of(new VectorStore.VectorMatch("chunk-1", 0.90d)),
                barrier,
                chunkObservedParallelism
            )),
            request -> awaitParallelBranch(barrier, hybridObservedParallelism),
            new ContextAssembler()
        );

        var context = strategy.retrieve(QueryRequest.builder()
            .query("mix parallel question")
            .mode(QueryMode.MIX)
            .chunkTopK(1)
            .build());

        assertThat(hybridObservedParallelism.get()).isTrue();
        assertThat(chunkObservedParallelism.get()).isTrue();
        assertThat(context.matchedChunks())
            .extracting(ScoredChunk::chunkId)
            .containsExactly("chunk-1");
    }

    @Test
    void mixPropagatesDirectChunkBranchFailure() {
        var delegate = InMemoryStorageProvider.create();
        var strategy = new MixQueryStrategy(
            new FakeEmbeddingModel(Map.of("mix failure question", List.of(1.0d, 0.0d))),
            new TestStorageProvider(delegate, new FailingHybridVectorStore(new IllegalStateException("chunk branch failed"))),
            request -> new QueryContext(List.of(), List.of(), List.of(), ""),
            new ContextAssembler()
        );

        assertThatThrownBy(() -> strategy.retrieve(QueryRequest.builder()
            .query("mix failure question")
            .mode(QueryMode.MIX)
            .chunkTopK(1)
            .build()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("chunk branch failed");
    }

    private static QueryContext awaitParallelBranch(CyclicBarrier barrier, AtomicBoolean observedParallelism) {
        try {
            barrier.await(250, TimeUnit.MILLISECONDS);
            observedParallelism.set(true);
            return new QueryContext(List.of(), List.of(), List.of(), "");
        } catch (TimeoutException | BrokenBarrierException exception) {
            observedParallelism.set(false);
            return new QueryContext(List.of(), List.of(), List.of(), "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("parallel test interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("parallel branch failed", exception);
        }
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

    private static final class ChunkStoreOverrideStorageProvider implements StorageProvider {
        private final InMemoryStorageProvider delegate;
        private final HybridVectorStore vectorStore;
        private final ChunkStore chunkStore;

        private ChunkStoreOverrideStorageProvider(
            InMemoryStorageProvider delegate,
            HybridVectorStore vectorStore,
            ChunkStore chunkStore
        ) {
            this.delegate = delegate;
            this.vectorStore = vectorStore;
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

    private static final class CountingChunkStore implements ChunkStore {
        private final ChunkStore delegate;
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger loadAllCalls = new AtomicInteger();

        private CountingChunkStore(ChunkStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ChunkRecord chunk) {
            delegate.save(chunk);
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            loadCalls.incrementAndGet();
            return delegate.load(chunkId);
        }

        @Override
        public Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
            loadAllCalls.incrementAndGet();
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

        private int loadCalls() {
            return loadCalls.get();
        }

        private int loadAllCalls() {
            return loadAllCalls.get();
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

    private static final class BarrierHybridVectorStore implements HybridVectorStore {
        private final List<VectorMatch> matches;
        private final CyclicBarrier barrier;
        private final AtomicBoolean observedParallelism;
        private SearchRequest recordedRequest;

        private BarrierHybridVectorStore(List<VectorMatch> matches, CyclicBarrier barrier, AtomicBoolean observedParallelism) {
            this.matches = matches;
            this.barrier = barrier;
            this.observedParallelism = observedParallelism;
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            try {
                barrier.await(250, TimeUnit.MILLISECONDS);
                observedParallelism.set(true);
                this.recordedRequest = request;
                return matches.stream().limit(request.topK()).toList();
            } catch (TimeoutException | BrokenBarrierException exception) {
                observedParallelism.set(false);
                this.recordedRequest = request;
                return matches.stream().limit(request.topK()).toList();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("direct chunk barrier interrupted", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("direct chunk barrier failed", exception);
            }
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
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }

    private static final class FailingHybridVectorStore implements HybridVectorStore {
        private final RuntimeException failure;

        private FailingHybridVectorStore(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            throw failure;
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            throw failure;
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }
}
