package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusVectorStoreTest {
    @Test
    void saveAllEnrichedCreatesCollectionAndPersistsFullTextPayload() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(
            adapter,
            new MilvusVectorConfig(
                "http://localhost:19530",
                "root:Milvus",
                null,
                null,
                "default",
                "rag_",
                3
            ),
            "alpha"
        );

        store.saveAllEnriched(
            "chunks",
            List.of(new HybridVectorStore.EnrichedVectorRecord(
                "chunk-1",
                List.of(1.0d, 0.0d, 0.0d),
                "semantic body",
                List.of("java", "sdk")
            ))
        );

        assertThat(adapter.ensureCollectionRequests).hasSize(1);
        assertThat(adapter.ensureCollectionRequests.get(0).collectionName()).isEqualTo("rag");
        assertThat(adapter.ensureCollectionRequests.get(0).vectorDimensions()).isEqualTo(3);
        assertThat(adapter.ensureCollectionRequests.get(0).analyzerType()).isEqualTo("chinese");
        assertThat(adapter.upsertedRows.get("rag")).singleElement().satisfies(row -> {
            assertThat(row.pkId()).startsWith("pk-");
            assertThat(row.vectorId()).isEqualTo("chunk-1");
            assertThat(row.workspaceId()).isEqualTo("alpha");
            assertThat(row.recordType()).isEqualTo("chunks");
            assertThat(row.id()).isEqualTo("chunk-1");
            assertThat(row.denseVector()).containsExactly(1.0d, 0.0d, 0.0d);
            assertThat(row.searchableText()).isEqualTo("semantic body");
            assertThat(row.keywords()).containsExactly("java", "sdk");
            assertThat(row.fullText()).isEqualTo("semantic body\njava sdk");
        });
    }

    @Test
    void semanticSearchDelegatesToDenseChannel() {
        var adapter = new FakeMilvusClientAdapter();
        adapter.semanticResults = List.of(new VectorStore.VectorMatch("chunk-2", 0.91d));
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");

        var matches = store.search(
            "chunks",
            new HybridVectorStore.SearchRequest(
                List.of(0.3d, 0.2d, 0.1d),
                "",
                List.of(),
                HybridVectorStore.SearchMode.SEMANTIC,
                5
            )
        );

        assertThat(matches).containsExactly(new VectorStore.VectorMatch("chunk-2", 0.91d));
        assertThat(adapter.lastSemanticRequest).isEqualTo(
            new MilvusClientAdapter.SemanticSearchRequest(
                "rag",
                List.of(0.3d, 0.2d, 0.1d),
                5,
                namespaceFilter("alpha", "chunks")
            )
        );
    }

    @Test
    void keywordSearchDelegatesToBm25ChannelWithMergedQueryText() {
        var adapter = new FakeMilvusClientAdapter();
        adapter.keywordResults = List.of(new VectorStore.VectorMatch("chunk-3", 8.4d));
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");

        var matches = store.search(
            "chunks",
            new HybridVectorStore.SearchRequest(
                List.of(),
                "semantic query",
                List.of("java", "sdk"),
                HybridVectorStore.SearchMode.KEYWORD,
                4
            )
        );

        assertThat(matches).containsExactly(new VectorStore.VectorMatch("chunk-3", 8.4d));
        assertThat(adapter.lastKeywordRequest).isEqualTo(
            new MilvusClientAdapter.KeywordSearchRequest(
                "rag",
                "semantic query java sdk",
                4,
                namespaceFilter("alpha", "chunks")
            )
        );
    }

    @Test
    void hybridSearchDelegatesToDenseAndBm25ChannelsWithRrfRerankByDefault() {
        var adapter = new FakeMilvusClientAdapter();
        adapter.hybridResults = List.of(new VectorStore.VectorMatch("chunk-4", 0.77d));
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");

        var matches = store.search(
            "chunks",
            new HybridVectorStore.SearchRequest(
                List.of(0.9d, 0.1d, 0.0d),
                "hybrid query",
                List.of("milvus", "bm25"),
                HybridVectorStore.SearchMode.HYBRID,
                6
            )
        );

        assertThat(matches).containsExactly(new VectorStore.VectorMatch("chunk-4", 0.77d));
        assertThat(adapter.lastHybridRequest).isEqualTo(
            new MilvusClientAdapter.HybridSearchRequest(
                "rag",
                List.of(0.9d, 0.1d, 0.0d),
                "hybrid query milvus bm25",
                6,
                namespaceFilter("alpha", "chunks"),
                MilvusClientAdapter.HybridRankerType.RRF,
                List.of(),
                60
            )
        );
    }

    @Test
    void hybridSearchUsesWeightedRankerWhenConfigured() {
        var adapter = new FakeMilvusClientAdapter();
        adapter.hybridResults = List.of(new VectorStore.VectorMatch("chunk-5", 0.66d));
        var store = new MilvusVectorStore(
            adapter,
            new MilvusVectorConfig(
                "http://localhost:19530",
                "root:Milvus",
                null,
                null,
                "default",
                "rag_",
                3,
                "english",
                "weighted",
                99
            ),
            "alpha"
        );

        var matches = store.search(
            "chunks",
            new HybridVectorStore.SearchRequest(
                List.of(0.8d, 0.1d, 0.1d),
                "weighted query",
                List.of("milvus"),
                HybridVectorStore.SearchMode.HYBRID,
                3
            )
        );

        assertThat(matches).containsExactly(new VectorStore.VectorMatch("chunk-5", 0.66d));
        assertThat(adapter.lastHybridRequest).isEqualTo(
            new MilvusClientAdapter.HybridSearchRequest(
                "rag",
                List.of(0.8d, 0.1d, 0.1d),
                "weighted query milvus",
                3,
                namespaceFilter("alpha", "chunks"),
                MilvusClientAdapter.HybridRankerType.WEIGHTED,
                List.of(0.5f, 0.5f),
                99
            )
        );
    }

    @Test
    void listReturnsPersistedDenseVectorsInIdOrder() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");
        store.saveAll(
            "entities",
            List.of(
                new VectorStore.VectorRecord("entity-2", List.of(0.0d, 1.0d, 0.0d)),
                new VectorStore.VectorRecord("entity-1", List.of(1.0d, 0.0d, 0.0d))
            )
        );

        assertThat(store.list("entities")).containsExactly(
            new VectorStore.VectorRecord("entity-1", List.of(1.0d, 0.0d, 0.0d)),
            new VectorStore.VectorRecord("entity-2", List.of(0.0d, 1.0d, 0.0d))
        );
        assertThat(adapter.lastListRequest).isEqualTo(
            new MilvusClientAdapter.ListRequest("rag", namespaceFilter("alpha", "entities"))
        );
    }

    @Test
    void deleteNamespaceOnlyClearsOneLogicalSlice() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");
        store.saveAll(
            "chunks",
            List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)))
        );
        store.saveAll(
            "entities",
            List.of(new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d)))
        );

        store.deleteNamespace("chunks");

        assertThat(adapter.lastDeleteRequest).isEqualTo(
            new MilvusClientAdapter.DeleteRequest("rag", namespaceFilter("alpha", "chunks"))
        );
        assertThat(store.list("chunks")).isEmpty();
        assertThat(store.list("entities")).containsExactly(
            new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d))
        );
    }

    @Test
    void flushNamespacesDelegatesToMilvusWhenFlushOnWriteEnabled() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");

        store.flushNamespaces(List.of("chunks", "chunks", "entities"));

        assertThat(adapter.flushedCollectionNames).containsExactly("rag");
    }

    @Test
    void flushNamespacesSkipsMilvusWhenFlushOnWriteDisabled() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(
            adapter,
            new MilvusVectorConfig(
                "http://localhost:19530",
                "root:Milvus",
                null,
                null,
                "default",
                "rag_",
                3,
                "chinese",
                "rrf",
                60,
                MilvusVectorConfig.SchemaDriftStrategy.STRICT_FAIL,
                MilvusVectorConfig.QueryConsistency.BOUNDED,
                false
            ),
            "alpha"
        );

        store.flushNamespaces(List.of("chunks", "entities"));

        assertThat(adapter.flushedCollectionNames).isEmpty();
    }

    private static MilvusVectorConfig testConfig() {
        return new MilvusVectorConfig(
            "http://localhost:19530",
            "root:Milvus",
            null,
            null,
            "default",
            "rag_",
            3
        );
    }

    private static String namespaceFilter(String workspaceId, String namespace) {
        return "workspace_id == \"" + workspaceId + "\" && record_type == \"" + namespace + "\"";
    }

    private static final class FakeMilvusClientAdapter implements MilvusClientAdapter {
        private final List<MilvusClientAdapter.CollectionDefinition> ensureCollectionRequests = new ArrayList<>();
        private final Map<String, List<MilvusClientAdapter.StoredVectorRow>> upsertedRows = new LinkedHashMap<>();
        private MilvusClientAdapter.SemanticSearchRequest lastSemanticRequest;
        private MilvusClientAdapter.KeywordSearchRequest lastKeywordRequest;
        private MilvusClientAdapter.HybridSearchRequest lastHybridRequest;
        private MilvusClientAdapter.ListRequest lastListRequest;
        private MilvusClientAdapter.DeleteRequest lastDeleteRequest;
        private final List<String> flushedCollectionNames = new ArrayList<>();
        private List<VectorStore.VectorMatch> semanticResults = List.of();
        private List<VectorStore.VectorMatch> keywordResults = List.of();
        private List<VectorStore.VectorMatch> hybridResults = List.of();

        @Override
        public void ensureCollection(MilvusClientAdapter.CollectionDefinition collectionDefinition) {
            ensureCollectionRequests.add(collectionDefinition);
            upsertedRows.computeIfAbsent(collectionDefinition.collectionName(), ignored -> new ArrayList<>());
        }

        @Override
        public void upsert(String collectionName, List<MilvusClientAdapter.StoredVectorRow> rows) {
            upsertedRows.put(collectionName, new ArrayList<>(rows));
        }

        @Override
        public List<VectorStore.VectorRecord> list(String collectionName) {
            return list(new MilvusClientAdapter.ListRequest(collectionName, ""));
        }

        @Override
        public List<VectorStore.VectorRecord> list(MilvusClientAdapter.ListRequest request) {
            lastListRequest = request;
            return upsertedRows.getOrDefault(request.collectionName(), List.of()).stream()
                .filter(row -> matchesFilter(row, request.filter()))
                .sorted(java.util.Comparator.comparing(MilvusClientAdapter.StoredVectorRow::id))
                .map(row -> new VectorStore.VectorRecord(row.vectorId(), row.denseVector()))
                .toList();
        }

        @Override
        public List<VectorStore.VectorMatch> semanticSearch(MilvusClientAdapter.SemanticSearchRequest request) {
            lastSemanticRequest = request;
            return semanticResults;
        }

        @Override
        public List<VectorStore.VectorMatch> keywordSearch(MilvusClientAdapter.KeywordSearchRequest request) {
            lastKeywordRequest = request;
            return keywordResults;
        }

        @Override
        public List<VectorStore.VectorMatch> hybridSearch(MilvusClientAdapter.HybridSearchRequest request) {
            lastHybridRequest = request;
            return hybridResults;
        }

        @Override
        public void deleteAll(MilvusClientAdapter.DeleteRequest request) {
            lastDeleteRequest = request;
            upsertedRows.computeIfPresent(request.collectionName(), (collectionName, rows) -> rows.stream()
                .filter(row -> !matchesFilter(row, request.filter()))
                .toList());
        }

        @Override
        public void flush(List<String> collectionNames) {
            flushedCollectionNames.addAll(collectionNames);
        }

        @Override
        public void close() {
        }

        private static boolean matchesFilter(MilvusClientAdapter.StoredVectorRow row, String filter) {
            if (filter == null || filter.isBlank()) {
                return true;
            }
            var clauses = filter.split("&&");
            for (var clause : clauses) {
                var trimmed = clause.trim();
                if (trimmed.startsWith("workspace_id == \"")) {
                    if (!row.workspaceId().equals(extractQuotedValue(trimmed))) {
                        return false;
                    }
                } else if (trimmed.startsWith("record_type == \"")) {
                    if (!row.recordType().equals(extractQuotedValue(trimmed))) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static String extractQuotedValue(String clause) {
            var firstQuote = clause.indexOf('"');
            var lastQuote = clause.lastIndexOf('"');
            return clause.substring(firstQuote + 1, lastQuote);
        }
    }
}
