package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.FilteredVectorStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArcadeVectorStoreManualIntegrationTest {
    @Test
    void verifiesNativeVectorSparseAndRrfHybridSearch() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_IT", "false")));

        var config = ArcadeDbConfig.builder()
            .baseUrl(URI.create(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_URL", "http://localhost:2480")))
            .database(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_DATABASE", "lightrag"))
            .username(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_USERNAME", "root"))
            .password(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_PASSWORD", ""))
            .vectorDimensions(3)
            .timeout(Duration.ofSeconds(10))
            .initSchema(true)
            .build();
        var workspace = "manual-it-" + System.currentTimeMillis();
        try (var provider = new ArcadeStorageProvider(config, InMemoryStorageProvider.create().snapshotStore(), workspace)) {
            var hybridStore = (HybridVectorStore) provider.vectorStore();
            hybridStore.saveAllEnriched("chunks", List.of(
                new HybridVectorStore.EnrichedVectorRecord(
                    "semantic",
                    List.of(1.0, 0.0, 0.0),
                    "generic semantic chunk",
                    List.of("generic")
                ),
                new HybridVectorStore.EnrichedVectorRecord(
                    "keyword",
                    List.of(0.2, 0.0, 0.0),
                    "rental withdrawal process",
                    List.of("housing fund")
                )
            ));

            var semantic = provider.vectorStore().search("chunks", List.of(1.0, 0.0, 0.0), 2);
            assertThat(semantic).extracting(VectorStore.VectorMatch::id)
                .contains("semantic", "keyword");

            var keyword = hybridStore.search("chunks", new HybridVectorStore.SearchRequest(
                List.of(1.0, 0.0, 0.0),
                "housing fund rental withdrawal",
                List.of("process"),
                HybridVectorStore.SearchMode.KEYWORD,
                1
            ));
            assertThat(keyword).extracting(VectorStore.VectorMatch::id)
                .containsExactly("keyword");

            try {
                var hybrid = hybridStore.search("chunks", new HybridVectorStore.SearchRequest(
                    List.of(1.0, 0.0, 0.0),
                    "housing fund rental withdrawal",
                    List.of("process"),
                    HybridVectorStore.SearchMode.HYBRID,
                    2
                ));
                assertThat(hybrid).extracting(VectorStore.VectorMatch::id)
                    .contains("keyword", "semantic");
            } catch (io.github.lightrag.exception.StorageException exception) {
                assumeTrue(
                    !isMissingFuseFunction(exception),
                    "ArcadeDB image does not expose vector.fuse yet: " + exception.getMessage()
                );
                throw exception;
            }
        }
    }

    @Test
    void verifiesDynamicMetadataFilterAgainstArcadeDb() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_IT", "false")));

        var config = ArcadeDbConfig.builder()
            .baseUrl(URI.create(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_URL", "http://localhost:2480")))
            .database(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_DATABASE", "lightrag"))
            .username(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_USERNAME", "root"))
            .password(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_PASSWORD", ""))
            .vectorDimensions(3)
            .timeout(Duration.ofSeconds(10))
            .initSchema(true)
            .build();
        var workspace = "dynamic-filter-it-" + System.currentTimeMillis();
        try (var provider = new ArcadeStorageProvider(config, InMemoryStorageProvider.create().snapshotStore(), workspace)) {
            var hybridStore = (HybridVectorStore) provider.vectorStore();
            hybridStore.saveAllEnriched("chunks", List.of(
                new HybridVectorStore.EnrichedVectorRecord(
                    "east",
                    List.of(1.0, 0.0, 0.0),
                    "east housing policy",
                    List.of("housing"),
                    "",
                    "",
                    "",
                    "doc-east",
                    "chunk-east",
                    "text",
                    "chapter-east",
                    "policy.pdf",
                    "tenant-a",
                    "",
                    true,
                    Map.of("region", "east", "custom_department", "housing")
                ),
                new HybridVectorStore.EnrichedVectorRecord(
                    "west",
                    List.of(0.9, 0.0, 0.0),
                    "west medical policy",
                    List.of("medical"),
                    "",
                    "",
                    "",
                    "doc-west",
                    "chunk-west",
                    "text",
                    "chapter-west",
                    "policy.pdf",
                    "tenant-a",
                    "",
                    true,
                    Map.of("region", "west", "custom_department", "medical")
                )
            ));

            var matches = ((FilteredVectorStore) provider.vectorStore()).search(
                "chunks",
                List.of(1.0, 0.0, 0.0),
                2,
                new FilteredVectorStore.MetadataFilter(Map.of("custom_department", List.of("housing")), List.of())
            );

            assertThat(matches).extracting(VectorStore.VectorMatch::id)
                .containsExactly("east");
        }
    }

    @Test
    void documentsVectorFilterSubqueryBehavior() {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_IT", "false")));

        var config = ArcadeDbConfig.builder()
            .baseUrl(URI.create(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_URL", "http://localhost:2480")))
            .database(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_DATABASE", "lightrag"))
            .username(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_USERNAME", "root"))
            .password(System.getenv().getOrDefault("LIGHTRAG_ARCADEDB_PASSWORD", ""))
            .vectorDimensions(3)
            .timeout(Duration.ofSeconds(10))
            .initSchema(false)
            .build();
        var type = "FilterProbe" + System.currentTimeMillis();
        try (var client = new ArcadeDbClient(config)) {
            client.command("sql", "CREATE DOCUMENT TYPE " + type);
            client.command("sql", "CREATE PROPERTY " + type + ".workspaceId STRING");
            client.command("sql", "CREATE PROPERTY " + type + ".embedding ARRAY_OF_FLOATS");
            client.command("sql", "CREATE INDEX ON " + type + " (embedding) LSM_VECTOR METADATA { dimensions: 3, similarity: 'COSINE' }");
            client.command("sql", "INSERT INTO " + type + " SET workspaceId = ?, embedding = ?", "w1", List.of(1.0, 0.0, 0.0));
            client.command("sql", "INSERT INTO " + type + " SET workspaceId = ?, embedding = ?", "w2", List.of(1.0, 0.0, 0.0));

            var allowedRids = client.query("SELECT @rid AS rid FROM " + type + " WHERE workspaceId = ?", "w1").stream()
                .map(row -> row.get("rid").toString())
                .toList();
            var filtered = client.query(
                "SELECT workspaceId FROM (SELECT expand(vector.neighbors('" + type + "[embedding]', :queryVector, 2, { filter: :allowedRids })))",
                java.util.Map.of(
                    "queryVector", List.of(1.0, 0.0, 0.0),
                    "allowedRids", allowedRids
                )
            );

            assertThat(filtered).extracting(row -> row.get("workspaceId"))
                .containsExactly("w1");
        }
    }

    private static boolean isMissingFuseFunction(Throwable throwable) {
        var current = throwable;
        while (current != null) {
            var message = current.getMessage();
            if (message != null && message.contains("Unknown method name: fuse")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
