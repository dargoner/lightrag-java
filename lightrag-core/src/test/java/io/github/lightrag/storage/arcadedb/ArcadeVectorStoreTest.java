package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.BatchVectorStore;
import io.github.lightrag.storage.FilteredVectorStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArcadeVectorStoreTest {
    @Test
    void ranksVectorsByCosineSimilarity() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAll("chunks", List.of(
            new VectorStore.VectorRecord("a", List.of(1.0, 0.0, 0.0)),
            new VectorStore.VectorRecord("b", List.of(0.0, 1.0, 0.0)),
            new VectorStore.VectorRecord("c", List.of(0.8, 0.1, 0.0))
        ));

        var matches = store.search("chunks", List.of(1.0, 0.0, 0.0), 2);

        assertThat(matches).extracting(VectorStore.VectorMatch::id).containsExactly("a", "c");
    }

    @Test
    void fallsBackWhenNativeVectorQueryIsUnavailable() {
        var client = new FailingNativeQueryClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAll("chunks", List.of(
            new VectorStore.VectorRecord("a", List.of(1.0, 0.0, 0.0)),
            new VectorStore.VectorRecord("b", List.of(0.0, 1.0, 0.0))
        ));

        var matches = store.search("chunks", List.of(1.0, 0.0, 0.0), 1);

        assertThat(matches).extracting(VectorStore.VectorMatch::id).containsExactly("a");
    }

    @Test
    void supportsHybridSemanticAndBm25Search() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "semantic",
                List.of(1.0, 0.0, 0.0),
                "generic text",
                List.of()
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "keyword",
                List.of(0.2, 0.0, 0.0),
                "rental withdrawal process",
                List.of("housing fund")
            )
        ));

        var matches = store.search("chunks", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "housing fund rental withdrawal",
            List.of("process"),
            HybridVectorStore.SearchMode.HYBRID,
            2
        ));

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("keyword", "semantic");
    }

    @Test
    void keywordSearchUsesSparseIndexWithoutListingVectors() {
        var client = new SparseOnlyClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("entities", List.of(
            new HybridVectorStore.EnrichedVectorRecord("alice", List.of(1.0, 0.0, 0.0), "Alice", List.of("engineer")),
            new HybridVectorStore.EnrichedVectorRecord("bob", List.of(0.1, 0.0, 0.0), "Bob", List.of("rental withdrawal"))
        ));

        var matches = store.search("entities", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "rental withdrawal",
            List.of("rental withdrawal"),
            HybridVectorStore.SearchMode.KEYWORD,
            1
        ));

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("bob");
        assertThat(client.listQueries).isZero();
        assertThat(client.sparseQueries).isEqualTo(1);
    }

    @Test
    void chineseKeywordSearchUsesCjkSparseNgrams() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "rent",
                List.of(1.0, 0.0, 0.0),
                "\u79df\u623f\u63d0\u53d6\u6d41\u7a0b",
                List.of("\u4f4f\u623f\u516c\u79ef\u91d1")
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "medical",
                List.of(0.1, 0.0, 0.0),
                "\u533b\u4fdd\u62a5\u9500\u6750\u6599",
                List.of("\u793e\u4fdd")
            )
        ));

        var matches = store.search("chunks", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "\u516c\u79ef\u91d1\u63d0\u53d6",
            List.of(),
            HybridVectorStore.SearchMode.KEYWORD,
            1
        ));

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("rent");
        assertThat(new NgramSparseEncoder().tokens("\u4f4f\u623f\u516c\u79ef\u91d1\u63d0\u53d6"))
            .contains("\u516c\u79ef", "\u79ef\u91d1", "\u63d0\u53d6", "\u516c\u79ef\u91d1");
    }

    @Test
    void acceptsCustomSparseEncoder() {
        var client = new FakeArcadeClient();
        var encoder = new SparseEncoder() {
            @Override
            public SparseVector encodeDocument(String text, List<String> keywords) {
                return new SparseVector(List.of(7), List.of(1.0d));
            }

            @Override
            public SparseVector encodeQuery(String text, List<String> keywords) {
                return new SparseVector(List.of(7), List.of(1.0d));
            }
        };
        var store = new ArcadeVectorStore(client, "default", 3, encoder);
        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord("custom", List.of(1.0, 0.0, 0.0), "does not matter", List.of())
        ));

        var matches = store.search("chunks", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "query",
            List.of(),
            HybridVectorStore.SearchMode.KEYWORD,
            1
        ));

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("custom");
    }

    @Test
    void keywordSearchFailsWhenSparseIndexIsUnavailable() {
        var client = new FailingSparseClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("entities", List.of(
            new HybridVectorStore.EnrichedVectorRecord("bob", List.of(0.1, 0.0, 0.0), "Bob", List.of("rental withdrawal"))
        ));

        assertThatThrownBy(() -> store.search("entities", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "rental withdrawal",
            List.of("rental withdrawal"),
            HybridVectorStore.SearchMode.KEYWORD,
            1
        )))
            .isInstanceOf(io.github.lightrag.exception.StorageException.class)
            .hasMessageContaining("ArcadeDB sparse BM25 search failed");
        assertThat(client.listQueries).isZero();
    }

    @Test
    void batchSearchUsesHybridScoringWhenKeywordsArePresent() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("entities", List.of(
            new HybridVectorStore.EnrichedVectorRecord("alice", List.of(1.0, 0.0, 0.0), "Alice", List.of("engineer")),
            new HybridVectorStore.EnrichedVectorRecord("bob", List.of(0.1, 0.0, 0.0), "Bob", List.of("rental withdrawal"))
        ));

        var results = store.batchSearch(List.of(new BatchVectorStore.SearchSpec(
            "entities",
            "entities",
            List.of(1.0, 0.0, 0.0),
            "rental withdrawal",
            List.of("rental withdrawal"),
            1
        )));

        assertThat(results.get("entities")).extracting(VectorStore.VectorMatch::id)
            .containsExactly("bob");
    }

    @Test
    void filteredSearchPushesMetadataIntoVectorFilterSubquery() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "shanghai",
                List.of(1.0, 0.0, 0.0),
                "Shanghai rental withdrawal",
                List.of(),
                "",
                "",
                "",
                "doc-a",
                "chunk-a",
                "table",
                "chapter 1",
                "policy.pdf",
                "shanghai",
                "",
                true,
                Map.of("region", "east")
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "beijing",
                List.of(0.9, 0.0, 0.0),
                "Beijing rental withdrawal",
                List.of(),
                "",
                "",
                "",
                "doc-b",
                "chunk-b",
                "text",
                "chapter 2",
                "policy.pdf",
                "beijing",
                "",
                true,
                Map.of("region", "north")
            )
        ));

        var matches = store.search(
            "chunks",
            List.of(1.0, 0.0, 0.0),
            2,
            new FilteredVectorStore.MetadataFilter(Map.of("tenant_id", List.of("shanghai")), List.of())
        );

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("shanghai");
        assertThat(client.lastVectorSql)
            .contains("filter: (SELECT @rid FROM VectorEntry WHERE workspaceId = :workspaceId AND namespace = :namespace AND tenantId IN :filter_tenantId_1)");
        assertThat(client.lastVectorParams)
            .containsEntry("filter_tenantId_1", List.of("shanghai"));
    }

    @Test
    void dynamicMetadataFilterUsesVectorMetadataIndex() {
        var client = new FakeArcadeClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("chunks", List.of(
            new HybridVectorStore.EnrichedVectorRecord(
                "east",
                List.of(1.0, 0.0, 0.0),
                "East policy",
                List.of(),
                "",
                "",
                "",
                "doc-a",
                "chunk-a",
                "text",
                "chapter 1",
                "policy.pdf",
                "tenant-a",
                "",
                true,
                Map.of("region", "east", "department", "housing")
            ),
            new HybridVectorStore.EnrichedVectorRecord(
                "west",
                List.of(0.9, 0.0, 0.0),
                "West policy",
                List.of(),
                "",
                "",
                "",
                "doc-b",
                "chunk-b",
                "text",
                "chapter 2",
                "policy.pdf",
                "tenant-a",
                "",
                true,
                Map.of("region", "west", "department", "medical")
            )
        ));

        var matches = store.search(
            "chunks",
            List.of(1.0, 0.0, 0.0),
            2,
            new FilteredVectorStore.MetadataFilter(Map.of("region", List.of("east")), List.of())
        );

        assertThat(matches).extracting(VectorStore.VectorMatch::id)
            .containsExactly("east");
        assertThat(client.lastVectorSql)
            .contains("FROM VectorMetadata")
            .contains("field = :filter_metadataField_1")
            .contains("value IN :filter_metadataValue_1");
        assertThat(client.lastVectorParams)
            .containsEntry("filter_metadataField_1", "region")
            .containsEntry("filter_metadataValue_1", List.of("east"));
    }

    @Test
    void hybridSearchFailsWhenRrfFuseIsUnavailable() {
        var client = new FailingFuseClient();
        var store = new ArcadeVectorStore(client, "default", 3);
        store.saveAllEnriched("entities", List.of(
            new HybridVectorStore.EnrichedVectorRecord("bob", List.of(0.1, 0.0, 0.0), "Bob", List.of("rental withdrawal"))
        ));

        assertThatThrownBy(() -> store.search("entities", new HybridVectorStore.SearchRequest(
            List.of(1.0, 0.0, 0.0),
            "rental withdrawal",
            List.of("rental withdrawal"),
            HybridVectorStore.SearchMode.HYBRID,
            1
        )))
            .isInstanceOf(io.github.lightrag.exception.StorageException.class)
            .hasMessageContaining("ArcadeDB RRF hybrid search failed");
    }

    private static class FakeArcadeClient extends ArcadeDbClient {
        private final java.util.Map<String, StoredVector> vectors = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Map<String, String>> metadata = new java.util.LinkedHashMap<>();
        int listQueries;
        int sparseQueries;
        String lastVectorSql;
        Map<String, Object> lastVectorParams = Map.of();

        private FakeArcadeClient() {
            super(ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build());
        }

        @Override
        public List<Map<String, Object>> query(String sql, Object... parameters) {
            if (sql.startsWith("SELECT @rid")) {
                if (parameters.length == 2) {
                    return vectors.values().stream()
                        .map(vector -> Map.<String, Object>of("rid", vector.rid()))
                        .toList();
                }
                var id = parameters[2].toString();
                return vectors.containsKey(id) ? List.of(Map.of("@rid", vectors.get(id).rid())) : List.of();
            }
            if (sql.startsWith("SELECT id, embedding")) {
                listQueries++;
                return vectors.values().stream()
                    .map(StoredVector::toRow)
                    .toList();
            }
            return List.of();
        }

        @Override
        public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            if (sql.startsWith("SELECT @rid AS rid")) {
                return filterVectors(params).stream()
                    .map(vector -> Map.<String, Object>of("rid", vector.rid()))
                    .toList();
            }
            if (sql.startsWith("SELECT id, embedding")) {
                listQueries++;
                return filterVectors(params).stream()
                    .map(StoredVector::toRow)
                    .toList();
            }
            if (sql.contains("vector.fuse")) {
                lastVectorSql = sql;
                lastVectorParams = Map.copyOf(params);
                return fusedRows(params);
            }
            if (sql.contains("vector.neighbors") || sql.contains("vectorNeighbors")) {
                lastVectorSql = sql;
                lastVectorParams = Map.copyOf(params);
                var queryVector = ArcadeJsonCodec.readDoubleList(params.get("queryVector"));
                var topK = ((Number) params.get("topK")).intValue();
                return filterVectors(params).stream()
                    .map(vector -> Map.<String, Object>of(
                        "id", vector.id(),
                        "distance", 1.0d - cosine(queryVector, vector.embedding())
                    ))
                    .sorted(Comparator.comparingDouble(row -> ((Number) row.get("distance")).doubleValue()))
                    .limit(topK)
                    .toList();
            }
            if (sql.contains("vector.sparseNeighbors")) {
                lastVectorSql = sql;
                lastVectorParams = Map.copyOf(params);
                sparseQueries++;
                var queryTokens = ArcadeJsonCodec.convert(params.get("sparseTokens"), List.class);
                var topK = ((Number) params.getOrDefault("topK", params.get("candidateTopK"))).intValue();
                return filterVectors(params).stream()
                    .map(vector -> Map.<String, Object>of(
                        "id", vector.id(),
                        "score", sparseScore(vector.sparseTokens(), queryTokens)
                    ))
                    .filter(row -> ((Number) row.get("score")).doubleValue() > 0.0d)
                    .sorted(Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("score")).doubleValue()).reversed())
                    .limit(topK)
                    .toList();
            }
            return super.query(sql, params);
        }

        @Override
        public List<Map<String, Object>> command(String language, String command, Object... parameters) {
            if (command.startsWith("INSERT INTO VectorEntry")) {
                vectors.put(parameters[2].toString(), new StoredVector(
                    "#1:" + vectors.size(),
                    parameters[2].toString(),
                    ArcadeJsonCodec.readDoubleList(parameters[3]),
                    parameters[4].toString(),
                    ArcadeJsonCodec.readStringList(parameters[5]),
                    ArcadeJsonCodec.convert(parameters[6], List.class),
                    parameters[8].toString(),
                    parameters[9].toString(),
                    parameters[10].toString(),
                    parameters[11].toString(),
                    parameters[12].toString(),
                    parameters[13].toString(),
                    parameters[14].toString(),
                    parameters[15].toString(),
                    parameters[16].toString(),
                    parameters[17].toString(),
                    Boolean.parseBoolean(parameters[18].toString())
                ));
            } else if (command.startsWith("UPDATE VectorEntry")) {
                var existing = vectors.get(parameters[18].toString());
                vectors.put(parameters[18].toString(), new StoredVector(
                    existing == null ? "#1:" + vectors.size() : existing.rid(),
                    parameters[18].toString(),
                    ArcadeJsonCodec.readDoubleList(parameters[0]),
                    parameters[1].toString(),
                    ArcadeJsonCodec.readStringList(parameters[2]),
                    ArcadeJsonCodec.convert(parameters[3], List.class),
                    parameters[5].toString(),
                    parameters[6].toString(),
                    parameters[7].toString(),
                    parameters[8].toString(),
                    parameters[9].toString(),
                    parameters[10].toString(),
                    parameters[11].toString(),
                    parameters[12].toString(),
                    parameters[13].toString(),
                    parameters[14].toString(),
                    Boolean.parseBoolean(parameters[15].toString())
                ));
            } else if (command.startsWith("DELETE FROM VectorMetadata")) {
                metadata.remove(parameters[2].toString());
            } else if (command.startsWith("INSERT INTO VectorMetadata")) {
                metadata.computeIfAbsent(parameters[2].toString(), ignored -> new java.util.LinkedHashMap<>())
                    .put(parameters[3].toString(), parameters[4].toString());
            }
            return List.of();
        }

        private List<Map<String, Object>> fusedRows(Map<String, Object> params) {
            var queryVector = ArcadeJsonCodec.readDoubleList(params.get("queryVector"));
            var queryTokens = ArcadeJsonCodec.convert(params.get("sparseTokens"), List.class);
            var topK = ((Number) params.get("topK")).intValue();
            var candidates = filterVectors(params);
            var vectorRanks = rankedVectorIds(candidates, queryVector);
            var textRanks = rankedSparseIds(candidates, queryTokens);
            var scores = new java.util.LinkedHashMap<String, Double>();
            for (int index = 0; index < vectorRanks.size(); index++) {
                scores.merge(vectorRanks.get(index), 1.0d / (60.0d + index + 1), Double::sum);
            }
            for (int index = 0; index < textRanks.size(); index++) {
                scores.merge(textRanks.get(index), 1.0d / (60.0d + index + 1), Double::sum);
            }
            return scores.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .limit(topK)
                .map(entry -> Map.<String, Object>of("id", entry.getKey(), "score", entry.getValue()))
                .toList();
        }

        private List<StoredVector> filterVectors(Map<String, Object> params) {
            return vectors.values().stream()
                .filter(vector -> matchesListFilter(params, "filter_documentId_1", vector.documentId()))
                .filter(vector -> matchesListFilter(params, "filter_sourceId_1", vector.sourceId()))
                .filter(vector -> matchesListFilter(params, "filter_filePath_1", vector.filePath()))
                .filter(vector -> matchesListFilter(params, "filter_contentType_1", vector.contentType()))
                .filter(vector -> matchesListFilter(params, "filter_sectionPath_1", vector.sectionPath()))
                .filter(vector -> matchesListFilter(params, "filter_source_1", vector.source()))
                .filter(vector -> matchesListFilter(params, "filter_tenantId_1", vector.tenantId()))
                .filter(vector -> matchesListFilter(params, "filter_createdAt_1", vector.createdAt()))
                .filter(vector -> matchesBooleanFilter(params, "filter_searchable_1", vector.searchable()))
                .filter(vector -> matchesDynamicMetadataFilter(params, vector.id()))
                .toList();
        }

        private List<String> rankedVectorIds(List<StoredVector> candidates, List<Double> queryVector) {
            return candidates.stream()
                .sorted(Comparator.comparingDouble(vector -> 1.0d - cosine(queryVector, vector.embedding())))
                .map(StoredVector::id)
                .toList();
        }

        private List<String> rankedSparseIds(List<StoredVector> candidates, List<?> queryTokens) {
            return candidates.stream()
                .map(vector -> Map.<String, Object>of("id", vector.id(), "score", sparseScore(vector.sparseTokens(), queryTokens)))
                .filter(row -> ((Number) row.get("score")).doubleValue() > 0.0d)
                .sorted(Comparator.<Map<String, Object>>comparingDouble(row -> ((Number) row.get("score")).doubleValue()).reversed())
                .map(row -> row.get("id").toString())
                .toList();
        }


        private static double cosine(List<Double> left, List<Double> right) {
            double dot = 0.0d;
            double leftNorm = 0.0d;
            double rightNorm = 0.0d;
            for (int i = 0; i < left.size(); i++) {
                dot += left.get(i) * right.get(i);
                leftNorm += left.get(i) * left.get(i);
                rightNorm += right.get(i) * right.get(i);
            }
            if (leftNorm == 0.0d || rightNorm == 0.0d) {
                return 0.0d;
            }
            return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        }

        private static double sparseScore(List<Integer> documentTokens, List<?> queryTokens) {
            var score = 0.0d;
            for (var token : queryTokens) {
                if (documentTokens.contains(((Number) token).intValue())) {
                    score += 1.0d;
                }
            }
            return score;
        }

        private static boolean matchesListFilter(Map<String, Object> params, String key, String value) {
            if (!params.containsKey(key)) {
                return true;
            }
            return ((List<?>) params.get(key)).contains(value);
        }

        private static boolean matchesBooleanFilter(Map<String, Object> params, String key, boolean value) {
            if (!params.containsKey(key)) {
                return true;
            }
            return ((List<?>) params.get(key)).contains(value);
        }

        private boolean matchesDynamicMetadataFilter(Map<String, Object> params, String vectorId) {
            if (!params.containsKey("filter_metadataField_1")) {
                return true;
            }
            var vectorMetadata = metadata.getOrDefault(vectorId, Map.of());
            var field = params.get("filter_metadataField_1").toString();
            var expected = (List<?>) params.get("filter_metadataValue_1");
            return expected.contains(vectorMetadata.get(field));
        }

        private record StoredVector(
            String rid,
            String id,
            List<Double> embedding,
            String searchableText,
            List<String> keywords,
            List<?> sparseTokenValues,
            String srcId,
            String tgtId,
            String filePath,
            String documentId,
            String sourceId,
            String contentType,
            String sectionPath,
            String source,
            String tenantId,
            String createdAt,
            boolean searchable
        ) {
            private Map<String, Object> toRow() {
                return Map.of(
                    "id", id,
                    "embedding", ArcadeJsonCodec.writeDoubleList(embedding),
                    "searchableText", searchableText,
                    "keywords", ArcadeJsonCodec.writeJson(keywords),
                    "sparseTokens", sparseTokenValues
                );
            }

            private List<Integer> sparseTokens() {
                return sparseTokenValues.stream()
                    .map(token -> ((Number) token).intValue())
                    .toList();
            }
        }
    }

    private static final class FailingNativeQueryClient extends FakeArcadeClient {
        @Override
        public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            if (sql.contains("vector.neighbors")) {
                throw new IllegalStateException("native vector path unavailable");
            }
            return super.query(sql, params);
        }
    }

    private static final class SparseOnlyClient extends FakeArcadeClient {
        @Override
        public List<Map<String, Object>> query(String sql, Object... parameters) {
            if (sql.startsWith("SELECT id, embedding")) {
                throw new AssertionError("keyword search should not list all vectors");
            }
            return super.query(sql, parameters);
        }
    }

    private static final class FailingSparseClient extends FakeArcadeClient {
        @Override
        public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            if (sql.contains("vector.sparseNeighbors")) {
                throw new IllegalStateException("sparse vector index unavailable");
            }
            return super.query(sql, params);
        }
    }

    private static final class FailingFuseClient extends FakeArcadeClient {
        @Override
        public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
            if (sql.contains("vector.fuse")) {
                throw new IllegalStateException("rrf fuse unavailable");
            }
            return super.query(sql, params);
        }
    }
}
