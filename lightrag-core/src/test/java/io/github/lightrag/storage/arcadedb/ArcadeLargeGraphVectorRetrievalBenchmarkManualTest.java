package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.OneShotRetrievalStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.FilteredVectorStore;
import io.github.lightrag.exception.StorageException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArcadeLargeGraphVectorRetrievalBenchmarkManualTest {
    private static final URI DEFAULT_ARCADE_BASE_URL = URI.create("http://localhost:2480");
    private static final String DEFAULT_ARCADE_USERNAME = "root";
    private static final String DEFAULT_ARCADE_PASSWORD = "playwithdata";
    private static final int VECTOR_DIMENSIONS = 3;
    private static final int TOP_K = 20;

    @Test
    void benchmarksOneHundredThousandSyntheticNodesVectorsAndRelations() throws Exception {
        assumeTrue(Boolean.parseBoolean(env("LIGHTRAG_ARCADEDB_IT", "false")));
        assumeTrue(Boolean.parseBoolean(env("LIGHTRAG_ARCADEDB_100K_BENCH", "false")));

        var config = arcadeConfig();
        ensureArcadeDatabase(config);
        var workspace = env("LIGHTRAG_ARCADEDB_100K_WORKSPACE", "synthetic-100k-" + System.currentTimeMillis());
        var entityCount = Integer.parseInt(env("LIGHTRAG_ARCADEDB_100K_ENTITIES", "100000"));
        var relationCount = Integer.parseInt(env("LIGHTRAG_ARCADEDB_100K_RELATIONS", "100000"));
        var chunkCount = Integer.parseInt(env("LIGHTRAG_ARCADEDB_100K_CHUNKS", "10000"));
        var batchSize = Integer.parseInt(env("LIGHTRAG_ARCADEDB_100K_BATCH_SIZE", "1000"));

        try (var provider = new ArcadeStorageProvider(config, InMemoryStorageProvider.create().snapshotStore(), workspace);
             var client = new ArcadeDbClient(config)) {
            var loadStarted = System.nanoTime();
            loadSyntheticDataset(client, workspace, entityCount, relationCount, chunkCount, batchSize);
            var loadMs = elapsedMs(loadStarted);

            var vectorStore = provider.vectorStore();
            var oneShotStore = (OneShotRetrievalStore) provider;
            warmup(vectorStore, oneShotStore);

            var report = new LinkedHashMap<String, Object>();
            report.put("workspace", workspace);
            report.put("entityCount", entityCount);
            report.put("relationCount", relationCount);
            report.put("chunkCount", chunkCount);
            report.put("vectorCount", entityCount + relationCount + chunkCount);
            report.put("vectorDimensions", VECTOR_DIMENSIONS);
            report.put("batchSize", batchSize);
            report.put("loadMs", loadMs);
            report.put("benchmarks", List.of(
                benchmarkSearch("entities", vectorStore, topicVector(0), "entities-topic-0"),
                benchmarkSearch("relations", vectorStore, topicVector(1), "relations-topic-1"),
                benchmarkSearch("chunks", vectorStore, topicVector(2), "chunks-topic-2"),
                benchmarkFilteredDense(provider.vectorStore()),
                benchmarkFilteredKeyword((io.github.lightrag.storage.HybridVectorStore) provider.vectorStore()),
                benchmarkFilteredHybrid((io.github.lightrag.storage.HybridVectorStore) provider.vectorStore()),
                benchmarkMix(oneShotStore, vectorStore)
            ));

            var reportPath = Path.of("build", "reports", "arcadedb-100k-graph-vector-benchmark.json");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, ArcadeJsonCodec.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));

            assertThat(count(client, "Entity", workspace)).isEqualTo(entityCount);
            assertThat(count(client, "Relation", workspace)).isEqualTo(relationCount);
            assertThat(count(client, "Chunk", workspace)).isEqualTo(chunkCount);
        }
    }

    @Test
    void benchmarksExistingSyntheticWorkspaceQueriesOnly() throws Exception {
        assumeTrue(Boolean.parseBoolean(env("LIGHTRAG_ARCADEDB_IT", "false")));
        assumeTrue(Boolean.parseBoolean(env("LIGHTRAG_ARCADEDB_QUERY_ONLY_BENCH", "false")));

        var config = arcadeConfig();
        var workspace = env("LIGHTRAG_ARCADEDB_100K_WORKSPACE", "");
        assumeTrue(!workspace.isBlank(), "LIGHTRAG_ARCADEDB_100K_WORKSPACE is required for query-only benchmark");
        try (var provider = new ArcadeStorageProvider(config, InMemoryStorageProvider.create().snapshotStore(), workspace)) {
            var vectorStore = provider.vectorStore();
            var oneShotStore = (OneShotRetrievalStore) provider;
            warmup(vectorStore, oneShotStore);

            var report = new LinkedHashMap<String, Object>();
            report.put("workspace", workspace);
            report.put("queryOnly", true);
            report.put("vectorDimensions", VECTOR_DIMENSIONS);
            report.put("benchmarks", List.of(
                benchmarkSearch("entities", vectorStore, topicVector(0), "entities-topic-0"),
                benchmarkSearch("relations", vectorStore, topicVector(1), "relations-topic-1"),
                benchmarkSearch("chunks", vectorStore, topicVector(2), "chunks-topic-2"),
                benchmarkFilteredDense(provider.vectorStore()),
                benchmarkFilteredKeyword((io.github.lightrag.storage.HybridVectorStore) provider.vectorStore()),
                benchmarkFilteredHybrid((io.github.lightrag.storage.HybridVectorStore) provider.vectorStore()),
                benchmarkMix(oneShotStore, vectorStore)
            ));

            var reportPath = Path.of("build", "reports", "arcadedb-100k-query-only-benchmark.json");
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, ArcadeJsonCodec.OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private static ArcadeDbConfig arcadeConfig() {
        return ArcadeDbConfig.builder()
            .baseUrl(URI.create(env("LIGHTRAG_ARCADEDB_URL", DEFAULT_ARCADE_BASE_URL.toString())))
            .database(env("LIGHTRAG_ARCADEDB_100K_DATABASE", "lightrag-100k-" + Long.toHexString(System.currentTimeMillis())))
            .username(env("LIGHTRAG_ARCADEDB_USERNAME", DEFAULT_ARCADE_USERNAME))
            .password(env("LIGHTRAG_ARCADEDB_PASSWORD", DEFAULT_ARCADE_PASSWORD))
            .vectorDimensions(VECTOR_DIMENSIONS)
            .timeout(Duration.ofSeconds(Long.parseLong(env("LIGHTRAG_ARCADEDB_TIMEOUT_SECONDS", "120"))))
            .initSchema(true)
            .build();
    }

    private static void loadSyntheticDataset(
        ArcadeDbClient client,
        String workspace,
        int entityCount,
        int relationCount,
        int chunkCount,
        int batchSize
    ) {
        insertChunks(client, workspace, chunkCount, batchSize);
        insertEntities(client, workspace, entityCount, chunkCount, batchSize);
        insertRelations(client, workspace, relationCount, entityCount, chunkCount, batchSize);
        insertVectors(client, workspace, "chunks", chunkCount, batchSize);
        insertVectors(client, workspace, "entities", entityCount, batchSize);
        insertVectors(client, workspace, "relations", relationCount, batchSize);
    }

    private static void insertChunks(ArcadeDbClient client, String workspace, int count, int batchSize) {
        forEachBatch(count, batchSize, (start, end) -> {
            var rows = new ArrayList<Map<String, Object>>(end - start);
            for (var index = start; index < end; index++) {
                var row = baseRow(workspace, "chunk-" + index);
                row.put("@type", "Chunk");
                row.put("documentId", "doc-" + (index / 100));
                row.put("text", "synthetic chunk topic " + topic(index) + " complaint housing fund policy node " + index);
                row.put("tokenCount", 32);
                row.put("chunkOrder", index);
                row.put("metadata", "{}");
                row.put("source", "synthetic");
                row.put("tenantId", "tenant-" + (index % 10));
                row.put("searchable", true);
                rows.add(row);
            }
            insertContent(client, "Chunk", rows);
        });
    }

    private static void insertEntities(ArcadeDbClient client, String workspace, int count, int chunkCount, int batchSize) {
        forEachBatch(count, batchSize, (start, end) -> {
            var rows = new ArrayList<Map<String, Object>>(end - start);
            for (var index = start; index < end; index++) {
                var row = baseRow(workspace, "entity-" + index);
                row.put("@type", "Entity");
                row.put("name", "entity topic " + topic(index) + " #" + index);
                row.put("type", "SyntheticTopic" + topic(index));
                row.put("description", "synthetic entity for large graph retrieval benchmark topic " + topic(index));
                row.put("aliases", "[]");
                row.put("sourceChunkIds", ArcadeJsonCodec.writeStringList(List.of("chunk-" + (index % chunkCount))));
                rows.add(row);
            }
            insertContent(client, "Entity", rows);
        });
    }

    private static void insertRelations(
        ArcadeDbClient client,
        String workspace,
        int count,
        int entityCount,
        int chunkCount,
        int batchSize
    ) {
        forEachBatch(count, batchSize, (start, end) -> {
            var rows = new ArrayList<Map<String, Object>>(end - start);
            for (var index = start; index < end; index++) {
                var src = index % entityCount;
                var tgt = (index * 31 + 7) % entityCount;
                var row = baseRow(workspace, "relation-" + index);
                row.put("@type", "Relation");
                row.put("srcId", "entity-" + src);
                row.put("tgtId", "entity-" + tgt);
                row.put("keywords", "topic-" + topic(index) + ",synthetic,relation");
                row.put("description", "synthetic relation topic " + topic(index) + " between entity-" + src + " and entity-" + tgt);
                row.put("weight", 1.0d + (index % 10) / 10.0d);
                row.put("sourceId", "chunk-" + (index % chunkCount));
                row.put("filePath", "synthetic://graph/" + topic(index));
                rows.add(row);
            }
            insertContent(client, "Relation", rows);
        });
    }

    private static void insertVectors(ArcadeDbClient client, String workspace, String namespace, int count, int batchSize) {
        forEachBatch(count, batchSize, (start, end) -> {
            var rows = new ArrayList<Map<String, Object>>(end - start);
            for (var index = start; index < end; index++) {
                var row = baseRow(workspace, idForNamespace(namespace, index));
                row.put("@type", "VectorEntry");
                row.put("namespace", namespace);
                row.put("embedding", vectorFor(index));
                row.put("searchableText", namespace + " topic " + topic(index) + " synthetic benchmark record " + index);
                row.put("keywords", ArcadeJsonCodec.writeStringList(List.of("topic-" + topic(index), namespace, "synthetic")));
                row.put("sparseTokens", List.of(topic(index) + 1, namespaceToken(namespace)));
                row.put("sparseWeights", List.of(1.0d, 1.0d));
                row.put("documentId", "doc-" + (index / 100));
                row.put("sourceId", "chunk-" + index);
                row.put("filePath", "synthetic://graph/" + topic(index));
                row.put("contentType", index % 5 == 0 ? "table" : "text");
                row.put("sectionPath", "section/" + topic(index));
                row.put("source", "synthetic");
                row.put("tenantId", "tenant-" + (index % 10));
                row.put("createdAt", "2026-05-23T00:00:00Z");
                row.put("searchable", true);
                rows.add(row);
            }
            insertContent(client, "VectorEntry", rows);
        });
    }

    private static Map<String, Object> benchmarkSearch(
        String namespace,
        VectorStore vectorStore,
        List<Double> queryVector,
        String label
    ) {
        var samples = new ArrayList<Sample>();
        for (var round = 0; round < 8; round++) {
            var started = System.nanoTime();
            var matches = vectorStore.search(namespace, queryVector, TOP_K);
            samples.add(new Sample(elapsedMs(started), matches));
        }
        var sortedMs = samples.stream().map(Sample::elapsedMs).sorted().toList();
        var best = samples.stream().min(Comparator.comparingDouble(Sample::elapsedMs)).orElseThrow();
        return Map.of(
            "name", label,
            "namespace", namespace,
            "topK", TOP_K,
            "avgMs", round(samples.stream().mapToDouble(Sample::elapsedMs).average().orElse(0.0d)),
            "p50Ms", round(percentile(sortedMs, 0.50d)),
            "p95Ms", round(percentile(sortedMs, 0.95d)),
            "bestMs", round(best.elapsedMs()),
            "topIds", best.matches().stream().map(VectorStore.VectorMatch::id).limit(10).toList()
        );
    }

    private static Map<String, Object> benchmarkFilteredDense(VectorStore vectorStore) {
        var filteredStore = (FilteredVectorStore) vectorStore;
        return benchmarkCall(
            "chunks-dense-filter-tenant-2",
            "chunks",
            () -> filteredStore.search(
                "chunks",
                topicVector(2),
                TOP_K,
                new FilteredVectorStore.MetadataFilter(Map.of("tenant_id", List.of("tenant-2")), List.of())
            )
        );
    }

    private static Map<String, Object> benchmarkFilteredKeyword(io.github.lightrag.storage.HybridVectorStore vectorStore) {
        var filteredStore = (FilteredVectorStore) vectorStore;
        var request = new io.github.lightrag.storage.HybridVectorStore.SearchRequest(
            topicVector(2),
            "topic-2 synthetic chunks",
            List.of("topic-2", "chunks"),
            io.github.lightrag.storage.HybridVectorStore.SearchMode.KEYWORD,
            TOP_K
        );
        return benchmarkCall(
            "chunks-keyword-filter-tenant-2",
            "chunks",
            () -> filteredStore.search(
                "chunks",
                request,
                new FilteredVectorStore.MetadataFilter(Map.of("tenant_id", List.of("tenant-2")), List.of())
            )
        );
    }

    private static Map<String, Object> benchmarkFilteredHybrid(io.github.lightrag.storage.HybridVectorStore vectorStore) {
        var filteredStore = (FilteredVectorStore) vectorStore;
        var request = new io.github.lightrag.storage.HybridVectorStore.SearchRequest(
            topicVector(2),
            "topic-2 synthetic chunks",
            List.of("topic-2", "chunks"),
            io.github.lightrag.storage.HybridVectorStore.SearchMode.HYBRID,
            TOP_K
        );
        return benchmarkCall(
            "chunks-hybrid-filter-tenant-2",
            "chunks",
            () -> filteredStore.search(
                "chunks",
                request,
                new FilteredVectorStore.MetadataFilter(Map.of("tenant_id", List.of("tenant-2")), List.of())
            )
        );
    }

    private static Map<String, Object> benchmarkCall(String label, String namespace, SearchCall call) {
        var samples = new ArrayList<Sample>();
        for (var round = 0; round < 8; round++) {
            var started = System.nanoTime();
            var matches = call.run();
            samples.add(new Sample(elapsedMs(started), matches));
        }
        var sortedMs = samples.stream().map(Sample::elapsedMs).sorted().toList();
        var best = samples.stream().min(Comparator.comparingDouble(Sample::elapsedMs)).orElseThrow();
        return Map.of(
            "name", label,
            "namespace", namespace,
            "topK", TOP_K,
            "avgMs", round(samples.stream().mapToDouble(Sample::elapsedMs).average().orElse(0.0d)),
            "p50Ms", round(percentile(sortedMs, 0.50d)),
            "p95Ms", round(percentile(sortedMs, 0.95d)),
            "bestMs", round(best.elapsedMs()),
            "topIds", best.matches().stream().map(VectorStore.VectorMatch::id).limit(10).toList()
        );
    }

    private static Map<String, Object> benchmarkMix(OneShotRetrievalStore oneShotStore, VectorStore vectorStore) {
        var samples = new ArrayList<MixSample>();
        for (var round = 0; round < 8; round++) {
            var entities = vectorStore.search("entities", topicVector(0), TOP_K);
            var relations = vectorStore.search("relations", topicVector(1), TOP_K);
            var chunks = vectorStore.search("chunks", topicVector(2), TOP_K);
            var started = System.nanoTime();
            var result = oneShotStore.retrieveMix(entities, relations, chunks);
            samples.add(new MixSample(
                elapsedMs(started),
                result.entities().size(),
                result.relations().size(),
                result.graphChunks().size(),
                result.directChunks().size()
            ));
        }
        var sortedMs = samples.stream().map(MixSample::elapsedMs).sorted().toList();
        var best = samples.stream().min(Comparator.comparingDouble(MixSample::elapsedMs)).orElseThrow();
        return Map.of(
            "name", "one-shot-mix-expand",
            "topK", TOP_K,
            "avgMs", round(samples.stream().mapToDouble(MixSample::elapsedMs).average().orElse(0.0d)),
            "p50Ms", round(percentile(sortedMs, 0.50d)),
            "p95Ms", round(percentile(sortedMs, 0.95d)),
            "bestMs", round(best.elapsedMs()),
            "bestEntityCount", best.entityCount(),
            "bestRelationCount", best.relationCount(),
            "bestGraphChunkCount", best.graphChunkCount(),
            "bestDirectChunkCount", best.directChunkCount()
        );
    }

    private static void warmup(VectorStore vectorStore, OneShotRetrievalStore oneShotStore) {
        var entities = vectorStore.search("entities", topicVector(0), TOP_K);
        var relations = vectorStore.search("relations", topicVector(1), TOP_K);
        var chunks = vectorStore.search("chunks", topicVector(2), TOP_K);
        oneShotStore.retrieveMix(entities, relations, chunks);
    }

    private static void insertContent(ArcadeDbClient client, String type, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        var command = "INSERT INTO " + type + " CONTENT " + ArcadeJsonCodec.writeJson(rows);
        var maxAttempts = Integer.parseInt(env("LIGHTRAG_ARCADEDB_100K_INSERT_RETRIES", "6"));
        for (var attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                client.command("sql", command);
                return;
            } catch (StorageException exception) {
                if (attempt == maxAttempts || !isRetryableInsertFailure(exception)) {
                    throw exception;
                }
                sleepQuietly((long) Math.min(5000, 150L * Math.pow(2, attempt - 1)));
            }
        }
    }

    private static boolean isRetryableInsertFailure(StorageException exception) {
        var message = exception.getMessage();
        if (message == null) {
            return false;
        }
        var normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("concurrentmodification")
            || normalized.contains("has been migrated")
            || normalized.contains("please retry")
            || normalized.contains("http 503");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while retrying ArcadeDB insert", exception);
        }
    }

    private static Map<String, Object> baseRow(String workspace, String id) {
        var row = new LinkedHashMap<String, Object>();
        row.put("workspaceId", workspace);
        row.put("id", id);
        return row;
    }

    private static int count(ArcadeDbClient client, String type, String workspace) {
        var rows = client.query("SELECT count(*) AS count FROM " + type + " WHERE workspaceId = ?", workspace);
        if (rows.isEmpty()) {
            return 0;
        }
        return ((Number) rows.get(0).get("count")).intValue();
    }

    private static void ensureArcadeDatabase(ArcadeDbConfig config) {
        var client = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        var request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(config.baseUrl().toString()) + "/api/v1/server"))
            .timeout(config.timeout())
            .header("Authorization", basicAuth(config.username(), config.password()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"create database " + config.database() + "\"}"))
            .build();
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new RuntimeException("Failed to ensure ArcadeDB database " + config.database(), exception);
        }
    }

    private static List<Double> vectorFor(int index) {
        var selectedTopic = topic(index);
        return topicVector(selectedTopic).stream()
            .map(value -> value + ((index % 17) * 0.0001d))
            .toList();
    }

    private static List<Double> topicVector(int topic) {
        return switch (topic % VECTOR_DIMENSIONS) {
            case 0 -> List.of(1.0d, 0.02d, 0.01d);
            case 1 -> List.of(0.01d, 1.0d, 0.02d);
            default -> List.of(0.02d, 0.01d, 1.0d);
        };
    }

    private static int topic(int index) {
        return Math.floorMod(index, VECTOR_DIMENSIONS);
    }

    private static String idForNamespace(String namespace, int index) {
        return switch (namespace) {
            case "entities" -> "entity-" + index;
            case "relations" -> "relation-" + index;
            case "chunks" -> "chunk-" + index;
            default -> namespace + "-" + index;
        };
    }

    private static int namespaceToken(String namespace) {
        return switch (namespace) {
            case "entities" -> 1001;
            case "relations" -> 1002;
            case "chunks" -> 1003;
            default -> 1999;
        };
    }

    private static void forEachBatch(int count, int batchSize, BatchConsumer consumer) {
        for (var start = 0; start < count; start += batchSize) {
            consumer.accept(start, Math.min(count, start + batchSize));
        }
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        var index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static double elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000.0d;
    }

    private static double round(double value) {
        return Double.parseDouble(String.format(Locale.ROOT, "%.3f", value));
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private interface BatchConsumer {
        void accept(int start, int end);
    }

    private interface SearchCall {
        List<VectorStore.VectorMatch> run();
    }

    private record Sample(double elapsedMs, List<VectorStore.VectorMatch> matches) {
    }

    private record MixSample(
        double elapsedMs,
        int entityCount,
        int relationCount,
        int graphChunkCount,
        int directChunkCount
    ) {
    }
}
