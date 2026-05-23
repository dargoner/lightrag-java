package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArcadeVectorStoreRetrievalBenchmarkManualTest {
    private static final int DOCS_PER_TOPIC = 120;
    private static final int TOP_K = 10;

    @Test
    void benchmarksChineseSparseDenseAndHybridRetrieval() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_IT", "false")));

        var config = ArcadeDbConfig.builder()
            .baseUrl(URI.create(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_URL", "http://localhost:2480")))
            .database(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_DATABASE", "lightrag"))
            .username(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_USERNAME", "root"))
            .password(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_PASSWORD", ""))
            .vectorDimensions(3)
            .timeout(Duration.ofSeconds(30))
            .initSchema(true)
            .build();
        var workspace = "retrieval-bench-" + System.currentTimeMillis();
        try (var provider = new ArcadeStorageProvider(config, InMemoryStorageProvider.create().snapshotStore(), workspace)) {
            var hybridStore = (HybridVectorStore) provider.vectorStore();
            hybridStore.saveAllEnriched("chunks", records());

            // Warm native indexes before measuring.
            for (var query : queries()) {
                runKeyword(hybridStore, query);
                runSemantic(provider.vectorStore(), query);
                runHybrid(hybridStore, query);
            }

            var report = new LinkedHashMap<String, Object>();
            report.put("workspace", workspace);
            report.put("docs", DOCS_PER_TOPIC * 3);
            report.put("topK", TOP_K);
            report.put("results", queries().stream()
                .map(query -> Map.of(
                    "query", query.text(),
                    "topic", query.topic(),
                    "keyword", measure(() -> runKeyword(hybridStore, query), query),
                    "semantic", measure(() -> runSemantic(provider.vectorStore(), query), query),
                    "hybrid", measure(() -> runHybrid(hybridStore, query), query)
                ))
                .toList());

            var path = Path.of("build", "reports", "arcadedb-retrieval-benchmark.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, ArcadeJsonCodec.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));

            @SuppressWarnings("unchecked")
            var results = (List<Map<String, Object>>) report.get("results");
            for (var result : results) {
                assertQuality((Map<String, Object>) result.get("keyword"));
                assertQuality((Map<String, Object>) result.get("hybrid"));
            }
        }
    }

    private static List<HybridVectorStore.EnrichedVectorRecord> records() {
        var records = new ArrayList<HybridVectorStore.EnrichedVectorRecord>();
        for (var index = 0; index < DOCS_PER_TOPIC; index++) {
            records.add(record("housing", index, List.of(1.0, 0.05, 0.02),
                "\u4f4f\u623f\u516c\u79ef\u91d1\u79df\u623f\u63d0\u53d6\u7533\u8bf7\u6750\u6599 \u529e\u7406\u6d41\u7a0b \u516c\u79ef\u91d1\u8d26\u6237 \u989d\u5ea6 \u7ebf\u4e0a\u63d0\u4ea4",
                List.of("\u516c\u79ef\u91d1", "\u79df\u623f\u63d0\u53d6", "\u4f4f\u623f", "\u6750\u6599")));
            records.add(record("medical", index, List.of(0.04, 1.0, 0.03),
                "\u533b\u4fdd\u62a5\u9500\u95e8\u8bca\u4f4f\u9662\u53d1\u7968\u6e05\u5355 \u793e\u4fdd\u5361 \u533b\u7597\u4fdd\u9669 \u7ecf\u529e\u6d41\u7a0b",
                List.of("\u533b\u4fdd", "\u62a5\u9500", "\u793e\u4fdd", "\u53d1\u7968")));
            records.add(record("education", index, List.of(0.02, 0.05, 1.0),
                "\u4e49\u52a1\u6559\u80b2\u5165\u5b66\u62a5\u540d \u5b66\u7c4d\u6750\u6599 \u6237\u7c4d\u8bc1\u660e \u5b66\u533a\u5ba1\u6838 \u62db\u751f\u6d41\u7a0b",
                List.of("\u5165\u5b66", "\u62a5\u540d", "\u5b66\u7c4d", "\u6237\u7c4d")));
        }
        return records;
    }

    private static HybridVectorStore.EnrichedVectorRecord record(
        String topic,
        int index,
        List<Double> vector,
        String text,
        List<String> keywords
    ) {
        return new HybridVectorStore.EnrichedVectorRecord(topic + "-" + index, vector, text + " 编号" + index, keywords);
    }

    private static List<QueryCase> queries() {
        return List.of(
            new QueryCase("housing", "\u516c\u79ef\u91d1\u63d0\u53d6\u6750\u6599", List.of("\u516c\u79ef\u91d1", "\u63d0\u53d6"), List.of(1.0, 0.04, 0.02)),
            new QueryCase("medical", "\u533b\u4fdd\u62a5\u9500\u53d1\u7968\u6d41\u7a0b", List.of("\u533b\u4fdd", "\u62a5\u9500"), List.of(0.03, 1.0, 0.02)),
            new QueryCase("education", "\u5165\u5b66\u62a5\u540d\u5b66\u7c4d\u6750\u6599", List.of("\u5165\u5b66", "\u62a5\u540d"), List.of(0.02, 0.04, 1.0))
        );
    }

    private static List<VectorStore.VectorMatch> runKeyword(HybridVectorStore store, QueryCase query) {
        return store.search("chunks", new HybridVectorStore.SearchRequest(
            query.vector(),
            query.text(),
            query.keywords(),
            HybridVectorStore.SearchMode.KEYWORD,
            TOP_K
        ));
    }

    private static List<VectorStore.VectorMatch> runSemantic(VectorStore store, QueryCase query) {
        return store.search("chunks", query.vector(), TOP_K);
    }

    private static List<VectorStore.VectorMatch> runHybrid(HybridVectorStore store, QueryCase query) {
        return store.search("chunks", new HybridVectorStore.SearchRequest(
            query.vector(),
            query.text(),
            query.keywords(),
            HybridVectorStore.SearchMode.HYBRID,
            TOP_K
        ));
    }

    private static Map<String, Object> measure(SearchCall searchCall, QueryCase query) {
        var samples = new ArrayList<Sample>();
        for (var round = 0; round < 5; round++) {
            var started = System.nanoTime();
            var matches = searchCall.run();
            var elapsedMs = (System.nanoTime() - started) / 1_000_000.0d;
            samples.add(new Sample(elapsedMs, matches));
        }
        var bestMatches = samples.stream()
            .min(Comparator.comparingDouble(Sample::elapsedMs))
            .orElseThrow()
            .matches();
        var relevant = bestMatches.stream()
            .filter(match -> match.id().startsWith(query.topic() + "-"))
            .count();
        var firstRelevantRank = firstRelevantRank(bestMatches, query.topic());
        return Map.of(
            "avgMs", round(samples.stream().mapToDouble(Sample::elapsedMs).average().orElse(0.0d)),
            "p50Ms", round(percentile(samples.stream().map(Sample::elapsedMs).sorted().toList(), 0.5d)),
            "relevantAtK", relevant,
            "precisionAtK", round(relevant / (double) TOP_K),
            "mrr", round(firstRelevantRank == 0 ? 0.0d : 1.0d / firstRelevantRank),
            "topIds", bestMatches.stream().map(VectorStore.VectorMatch::id).toList()
        );
    }

    private static void assertQuality(Map<String, Object> metrics) {
        assertThat(((Number) metrics.get("precisionAtK")).doubleValue())
            .isGreaterThanOrEqualTo(0.8d);
        assertThat(((Number) metrics.get("mrr")).doubleValue())
            .isEqualTo(1.0d);
    }

    private static int firstRelevantRank(List<VectorStore.VectorMatch> matches, String topic) {
        for (var index = 0; index < matches.size(); index++) {
            if (matches.get(index).id().startsWith(topic + "-")) {
                return index + 1;
            }
        }
        return 0;
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        var index = Math.min(sorted.size() - 1, (int) Math.floor(percentile * (sorted.size() - 1)));
        return sorted.get(index);
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.3f", value));
    }

    private interface SearchCall {
        List<VectorStore.VectorMatch> run();
    }

    private record QueryCase(String topic, String text, List<String> keywords, List<Double> vector) {
    }

    private record Sample(double elapsedMs, List<VectorStore.VectorMatch> matches) {
    }
}
