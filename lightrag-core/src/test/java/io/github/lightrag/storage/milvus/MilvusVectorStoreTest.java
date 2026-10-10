package io.github.lightrag.storage.milvus;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void deleteIdsUsesTechnicalPrimaryKeysInsideLogicalSlice() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), "alpha");
        store.saveAll(
            "chunks",
            List.of(
                new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)),
                new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
            )
        );
        var pkId = adapter.upsertedRows.get("rag").stream()
            .filter(row -> row.vectorId().equals("chunk-1"))
            .findFirst()
            .orElseThrow()
            .pkId();

        store.deleteIds("chunks", List.of("chunk-1"));

        assertThat(adapter.lastDeleteRequest).isEqualTo(
            new MilvusClientAdapter.DeleteRequest(
                "rag",
                namespaceFilter("alpha", "chunks") + " && pk_id in [\"" + pkId + "\"]"
            )
        );
        assertThat(adapter.lastDeleteRequest.filter()).doesNotContain(" id ", "vector_id");
        assertThat(store.list("chunks")).containsExactly(
            new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
        );
    }

    @Test
    void workspaceSetStoreSearchesAndListsWithAnInFilter() {
        var adapter = new FakeMilvusClientAdapter();
        adapter.semanticResults = List.of(new VectorStore.VectorMatch("chunk-2", 0.91d));
        var store = new MilvusVectorStore(adapter, testConfig(), List.of("alpha", "beta"));

        var matches = store.search("chunks", List.of(0.3d, 0.2d, 0.1d), 5);
        store.list("entities");

        assertThat(matches).containsExactly(new VectorStore.VectorMatch("chunk-2", 0.91d));
        assertThat(adapter.lastSemanticRequest.filter())
            .isEqualTo("workspace_id in [\"alpha\", \"beta\"] && record_type == \"chunks\"");
        assertThat(adapter.lastListRequest.filter())
            .isEqualTo("workspace_id in [\"alpha\", \"beta\"] && record_type == \"entities\"");
    }

    @Test
    void workspaceSetStoreListsRowsFromEveryWorkspace() {
        var adapter = new FakeMilvusClientAdapter();
        new MilvusVectorStore(adapter, testConfig(), "alpha").saveAll(
            "chunks",
            List.of(new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d)))
        );
        new MilvusVectorStore(adapter, testConfig(), "beta").saveAll(
            "chunks",
            List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)))
        );
        var store = new MilvusVectorStore(adapter, testConfig(), List.of("alpha", "beta"));

        assertThat(store.list("chunks")).containsExactly(
            new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)),
            new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
        );
    }

    @Test
    void workspaceSetStoreRejectsRowAddressedOperations() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), List.of("alpha", "beta"));
        var record = enriched("chunk-1", "body");

        assertThatThrownBy(() -> store.saveAllEnriched("chunks", List.of(record)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("saveAllEnriched requires a single-workspace Milvus vector store")
            .hasMessageContaining("covers 2 workspaces");
        assertThatThrownBy(() -> store.deleteNamespace("chunks"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deleteNamespace requires a single-workspace Milvus vector store");
        assertThatThrownBy(() -> store.deleteIds("chunks", List.of("chunk-1")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deleteIds requires a single-workspace Milvus vector store");
        assertThatThrownBy(() -> store.readRows("chunks", List.of("chunk-1")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("readRows requires a single-workspace Milvus vector store");
        assertThatThrownBy(() -> store.writeRows("chunks", List.of(new MilvusClientAdapter.StoredVectorRow(
            "pk-1",
            "chunk-1",
            "alpha",
            "chunks",
            "chunk-1",
            List.of(1.0d, 0.0d, 0.0d),
            "body",
            List.of(),
            "body",
            "",
            "",
            ""
        ))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("writeRows requires a single-workspace Milvus vector store");
        assertThat(adapter.upsertedRows).isEmpty();
        assertThat(adapter.rowReadRequests).isEmpty();
    }

    @Test
    void workspaceSetStoreDeduplicatesWorkspacesAndRejectsEmptyLists() {
        var adapter = new FakeMilvusClientAdapter();
        var store = new MilvusVectorStore(adapter, testConfig(), List.of("alpha", "alpha", "beta"));

        store.list("chunks");

        assertThat(adapter.lastListRequest.filter())
            .isEqualTo("workspace_id in [\"alpha\", \"beta\"] && record_type == \"chunks\"");
        assertThatThrownBy(() -> new MilvusVectorStore(adapter, testConfig(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspaceIds must not be empty");
    }

    @Test
    void readRowsFiltersByTechnicalPrimaryKey() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        store.saveAllEnriched(
            "chunks",
            List.of(enriched("chunk-1", "first body"), enriched("chunk-2", "second body"))
        );

        var rows = store.readRows("chunks", List.of("chunk-1"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.vectorId()).isEqualTo("chunk-1");
            assertThat(row.fullText()).isEqualTo("first body");
        });
        assertThat(adapter.rowReadRequests).hasSize(1);
        var filter = adapter.rowReadRequests.get(0).filter();
        assertThat(filter).contains("workspace_id == \"alpha\"");
        assertThat(filter).contains("record_type == \"chunks\"");
        assertThat(filter).contains("pk_id in [");
        assertThat(filteredPkIds(adapter.rowReadRequests.get(0)))
            .containsExactly(rows.get(0).pkId());
    }

    @Test
    void readRowsPreservesPersistedFullTextAndEndpoints() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        store.saveAllEnriched("relations", List.of(new HybridVectorStore.EnrichedVectorRecord(
            "relation-1",
            List.of(0.0d, 1.0d, 0.0d),
            "relation body",
            List.of("links", "alpha"),
            "entity-1",
            "entity-2",
            "doc-1.pdf"
        )));

        var rows = store.readRows("relations", List.of("relation-1"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.fullText()).isEqualTo("relation body\nlinks alpha");
            assertThat(row.searchableText()).isEqualTo("relation body");
            assertThat(row.srcId()).isEqualTo("entity-1");
            assertThat(row.tgtId()).isEqualTo("entity-2");
            assertThat(row.filePath()).isEqualTo("doc-1.pdf");
            assertThat(row.keywords()).isEmpty();
        });
    }

    @Test
    void restorePreImageReupsertsExistingRowsAndDeletesAbsentOnes() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        var storageAdapter = new MilvusVectorStorageAdapter(store);
        store.saveAllEnriched("chunks", List.of(enriched("chunk-1", "original body")));
        var originalFullText = adapter.upsertedRows.get("rag").get(0).fullText();

        var preImage = storageAdapter.capturePreImage(
            Map.of("chunks", List.of("chunk-1", "chunk-2"))
        ).orElseThrow();

        store.saveAllEnriched(
            "chunks",
            List.of(enriched("chunk-1", "attempt body"), enriched("chunk-2", "attempt body"))
        );

        storageAdapter.restorePreImage(preImage);

        assertThat(adapter.upsertedRows.get("rag")).singleElement().satisfies(row -> {
            assertThat(row.vectorId()).isEqualTo("chunk-1");
            assertThat(row.searchableText()).isEqualTo("original body");
            assertThat(row.fullText()).isEqualTo(originalFullText);
            assertThat(row.denseVector()).containsExactly(1.0d, 0.0d, 0.0d);
        });
    }

    @Test
    void capturePreImageFallsBackWhenProjectionDoesNotSupportRowReads() {
        var storageAdapter = new MilvusVectorStorageAdapter(new LegacyProjection(), snapshot -> Map.of());

        var preImage = storageAdapter.capturePreImage(Map.of("chunks", List.of("chunk-1")));

        assertThat(preImage).isEmpty();
    }

    @Test
    void readRowsBatchesLargeIdSetsAndPreservesOrder() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        var ids = java.util.stream.IntStream.range(0, 600).mapToObj(index -> "chunk-" + index).toList();
        store.saveAllEnriched("chunks", ids.stream().map(id -> enriched(id, "body")).toList());

        var rows = store.readRows("chunks", ids);

        var batchSize = MilvusVectorStore.READ_ROWS_ID_BATCH_SIZE;
        var expectedBatches = (ids.size() + batchSize - 1) / batchSize;
        assertThat(adapter.rowReadRequests).hasSize(expectedBatches);
        for (var index = 0; index < expectedBatches; index++) {
            assertThat(filteredPkIds(adapter.rowReadRequests.get(index)))
                .hasSize(Math.min(batchSize, ids.size() - index * batchSize));
        }
        var requestedPkIds = new ArrayList<String>();
        adapter.rowReadRequests.forEach(request -> requestedPkIds.addAll(filteredPkIds(request)));
        assertThat(requestedPkIds).containsExactlyElementsOf(adapter.upsertedRows.get("rag").stream()
            .map(MilvusClientAdapter.StoredVectorRow::pkId)
            .toList());
        assertThat(rows).hasSize(ids.size());
        assertThat(rows).extracting(MilvusClientAdapter.StoredVectorRow::vectorId)
            .containsExactlyElementsOf(ids);
    }

    @Test
    void readRowsDeduplicatesIdsBeforeQuerying() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        store.saveAllEnriched(
            "chunks",
            List.of(enriched("chunk-1", "first body"), enriched("chunk-2", "second body"))
        );

        var rows = store.readRows("chunks", List.of("chunk-1", "chunk-2", "chunk-1"));

        assertThat(adapter.rowReadRequests).hasSize(1);
        assertThat(filteredPkIds(adapter.rowReadRequests.get(0))).hasSize(2);
        assertThat(rows).extracting(MilvusClientAdapter.StoredVectorRow::vectorId)
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void readRowsIssuesNoQueryForEmptyIds() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);

        assertThat(store.readRows("chunks", List.of())).isEmpty();
        assertThat(adapter.rowReadRequests).isEmpty();
    }

    @Test
    void readRowsReturnsRowsInRequestedOrderEvenWhenClientShuffles() {
        var adapter = new FakeMilvusClientAdapter();
        var store = newStore(adapter);
        var ids = java.util.stream.IntStream.range(0, 600).mapToObj(index -> "chunk-" + index).toList();
        store.saveAllEnriched("chunks", ids.stream().map(id -> enriched(id, "body")).toList());
        adapter.shuffleReadRows = true;

        var rows = store.readRows("chunks", ids);

        assertThat(rows).extracting(MilvusClientAdapter.StoredVectorRow::vectorId)
            .containsExactlyElementsOf(ids);
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

    private static MilvusVectorStore newStore(FakeMilvusClientAdapter adapter) {
        return new MilvusVectorStore(adapter, testConfig(), "alpha");
    }

    private static HybridVectorStore.EnrichedVectorRecord enriched(String id, String body) {
        return new HybridVectorStore.EnrichedVectorRecord(id, List.of(1.0d, 0.0d, 0.0d), body, List.of());
    }

    private static List<String> filteredPkIds(MilvusClientAdapter.RowReadRequest request) {
        var filter = request.filter();
        var start = filter.indexOf("pk_id in [");
        var body = filter.substring(start + "pk_id in [".length(), filter.lastIndexOf(']'));
        return java.util.Arrays.stream(body.split(","))
            .map(String::trim)
            .map(token -> token.substring(1, token.length() - 1))
            .toList();
    }

    private static String namespaceFilter(String workspaceId, String namespace) {
        return "workspace_id == \"" + workspaceId + "\" && record_type == \"" + namespace + "\"";
    }

    private static final class LegacyProjection implements MilvusVectorStorageAdapter.Projection {
        @Override
        public void saveAll(String namespace, List<VectorStore.VectorRecord> vectors) {
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return List.of();
        }

        @Override
        public List<VectorStore.VectorRecord> list(String namespace) {
            return List.of();
        }

        @Override
        public void saveAllEnriched(String namespace, List<HybridVectorStore.EnrichedVectorRecord> records) {
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, HybridVectorStore.SearchRequest request) {
            return List.of();
        }

        @Override
        public void deleteNamespace(String namespace) {
        }

        @Override
        public void deleteIds(String namespace, List<String> ids) {
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
        }

        @Override
        public void close() {
        }
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
        private final List<MilvusClientAdapter.RowReadRequest> rowReadRequests = new ArrayList<>();
        private boolean shuffleReadRows;
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
            var merged = new LinkedHashMap<String, MilvusClientAdapter.StoredVectorRow>();
            for (var row : upsertedRows.getOrDefault(collectionName, List.of())) {
                merged.put(row.pkId(), row);
            }
            for (var row : rows) {
                merged.put(row.pkId(), row);
            }
            upsertedRows.put(collectionName, new ArrayList<>(merged.values()));
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
        public List<MilvusClientAdapter.StoredVectorRow> readRows(MilvusClientAdapter.RowReadRequest request) {
            rowReadRequests.add(request);
            var rows = upsertedRows.getOrDefault(request.collectionName(), List.of()).stream()
                .filter(row -> matchesFilter(row, request.filter()))
                .map(FakeMilvusClientAdapter::toSdkReadRow)
                .toList();
            if (!shuffleReadRows) {
                return List.copyOf(rows);
            }
            var shuffled = new ArrayList<>(rows);
            java.util.Collections.reverse(shuffled);
            return List.copyOf(shuffled);
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

        private static MilvusClientAdapter.StoredVectorRow toSdkReadRow(MilvusClientAdapter.StoredVectorRow stored) {
            return new MilvusClientAdapter.StoredVectorRow(
                "",
                stored.vectorId(),
                "",
                "",
                stored.vectorId(),
                stored.denseVector(),
                stored.searchableText(),
                List.of(),
                stored.fullText(),
                stored.srcId(),
                stored.tgtId(),
                stored.filePath()
            );
        }

        private static boolean matchesFilter(MilvusClientAdapter.StoredVectorRow row, String filter) {
            if (filter == null || filter.isBlank()) {
                return true;
            }
            var clauses = filter.split("&&");
            for (var clause : clauses) {
                var trimmed = clause.trim();
                if (trimmed.startsWith("workspace_id in [")) {
                    if (!matchesInFilter(row.workspaceId(), trimmed)) {
                        return false;
                    }
                } else if (trimmed.startsWith("workspace_id == \"")) {
                    if (!row.workspaceId().equals(extractQuotedValue(trimmed))) {
                        return false;
                    }
                } else if (trimmed.startsWith("record_type == \"")) {
                    if (!row.recordType().equals(extractQuotedValue(trimmed))) {
                        return false;
                    }
                } else if (trimmed.startsWith("pk_id in [")) {
                    if (!matchesInFilter(row.pkId(), trimmed)) {
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

        private static boolean matchesInFilter(String value, String clause) {
            var firstBracket = clause.indexOf('[');
            var lastBracket = clause.lastIndexOf(']');
            if (firstBracket < 0 || lastBracket < firstBracket) {
                return false;
            }
            var rawValues = clause.substring(firstBracket + 1, lastBracket).split(",");
            for (var rawValue : rawValues) {
                var normalized = rawValue.trim();
                if (normalized.length() >= 2
                    && normalized.charAt(0) == '"'
                    && normalized.charAt(normalized.length() - 1) == '"'
                    && value.equals(normalized.substring(1, normalized.length() - 1))) {
                    return true;
                }
            }
            return false;
        }
    }
}
