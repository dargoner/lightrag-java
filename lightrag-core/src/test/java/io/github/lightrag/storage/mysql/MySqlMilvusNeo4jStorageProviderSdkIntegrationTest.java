package io.github.lightrag.storage.mysql;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import io.github.lightrag.storage.neo4j.Neo4jGraphConfig;
import io.github.lightrag.support.Neo4jTestContainers;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MySqlMilvusNeo4jStorageProviderSdkIntegrationTest {
    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName ETCD_IMAGE = DockerImageName.parse("quay.io/coreos/etcd:v3.5.25");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z");
    private static final DockerImageName MILVUS_IMAGE = DockerImageName.parse("milvusdb/milvus:v2.6.11");
    private static final Duration MILVUS_STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Container
    private static final Neo4jContainer<?> NEO4J = Neo4jTestContainers.create();

    @Container
    private static final GenericContainer<?> ETCD = new GenericContainer<>(ETCD_IMAGE)
        .withNetwork(NETWORK)
        .withNetworkAliases("etcd")
        .withCommand(
            "etcd",
            "-advertise-client-urls=http://etcd:2379",
            "-listen-client-urls",
            "http://0.0.0.0:2379",
            "--data-dir",
            "/etcd"
        )
        .withExposedPorts(2379)
        .waitingFor(Wait.forListeningPort())
        .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(MINIO_IMAGE)
        .withNetwork(NETWORK)
        .withNetworkAliases("minio")
        .withEnv("MINIO_ACCESS_KEY", "minioadmin")
        .withEnv("MINIO_SECRET_KEY", "minioadmin")
        .withCommand("minio", "server", "/minio_data", "--console-address", ":9001")
        .withExposedPorts(9000, 9001)
        .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000))
        .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    private static final GenericContainer<?> MILVUS = new GenericContainer<>(MILVUS_IMAGE)
        .dependsOn(ETCD, MINIO)
        .withNetwork(NETWORK)
        .withNetworkAliases("standalone")
        .withEnv("ETCD_ENDPOINTS", "etcd:2379")
        .withEnv("MINIO_ADDRESS", "minio:9000")
        .withEnv("MQ_TYPE", "woodpecker")
        .withCommand("milvus", "run", "standalone")
        .withExposedPorts(19530, 9091)
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withSecurityOpts(List.of("seccomp=unconfined")))
        .waitingFor(Wait.forHttp("/healthz").forPort(9091))
        .withStartupTimeout(MILVUS_STARTUP_TIMEOUT);

    @Test
    void commitsAcrossMySqlMilvusAndNeo4jStoresUsingOfficialSdkBackends() throws Exception {
        try (var provider = newProvider(uniqueScopeId())) {
            provider.writeAtomically(storage -> {
                storage.documentStore().save(new DocumentStore.DocumentRecord(
                    "doc-1",
                    "Official SDK",
                    "Milvus semantic retrieval",
                    Map.of("source", "sdk")
                ));
                storage.chunkStore().save(new ChunkStore.ChunkRecord(
                    "doc-1:0",
                    "doc-1",
                    "Milvus semantic retrieval with official SDK",
                    6,
                    0,
                    Map.of("source", "sdk")
                ));
                storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-1",
                    DocumentStatus.PROCESSED,
                    "indexed",
                    null
                ));
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Milvus",
                    "database",
                    "Vector database",
                    List.of("official-sdk"),
                    List.of("doc-1:0")
                ));
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-2",
                    "supports",
                    "Milvus supports official SDK hybrid retrieval",
                    1.0d,
                    List.of("doc-1:0")
                ));
                storage.vectorStore().saveAll(
                    "chunks",
                    List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)))
                );
                return null;
            });

            assertThat(provider.documentStore().load("doc-1")).isPresent();
            assertThat(provider.chunkStore().load("doc-1:0")).isPresent();
            assertThat(provider.documentStatusStore().load("doc-1")).contains(
                new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "indexed", null)
            );
            assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
            assertThat(provider.graphStore().loadRelation("relation-1")).isPresent();
            assertThat(provider.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));

            var hybridStore = (HybridVectorStore) provider.vectorStore();
            assertThat(awaitMatches(() -> hybridStore.search(
                "chunks",
                new HybridVectorStore.SearchRequest(
                    List.of(1.0d, 0.0d, 0.0d),
                    "",
                    List.of(),
                    HybridVectorStore.SearchMode.SEMANTIC,
                    1
                )
            )))
                .extracting(VectorStore.VectorMatch::id)
                .containsExactly("doc-1:0");
        }
    }

    @Test
    void restoreReplacesStateAndSupportsSemanticKeywordAndHybridSearchUsingOfficialSdk() throws Exception {
        var replacement = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord(
                "doc-restore",
                "Restore Title",
                "Milvus hybrid search",
                Map.of("source", "official-sdk")
            )),
            List.of(new ChunkStore.ChunkRecord(
                "doc-restore:0",
                "doc-restore",
                "Milvus hybrid search with official sdk bm25 retrieval",
                8,
                0,
                Map.of("source", "official-sdk")
            )),
            List.of(new GraphStore.EntityRecord(
                "entity-restore",
                "Milvus",
                "database",
                "Hybrid retrieval engine",
                List.of("bm25", "semantic"),
                List.of("doc-restore:0")
            )),
            List.of(new GraphStore.RelationRecord(
                "relation-restore",
                "entity-restore",
                "entity-target",
                "supports",
                "Milvus supports semantic keyword hybrid retrieval",
                1.0d,
                List.of("doc-restore:0")
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-restore:0", List.of(1.0d, 0.0d, 0.0d)))),
            List.of(new DocumentStatusStore.StatusRecord("doc-restore", DocumentStatus.PROCESSED, "restored", null))
        );

        try (var provider = newProvider(uniqueScopeId())) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old", "Old", Map.of()));
            provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-old:0", "doc-old", "Old", 3, 0, Map.of()));
            provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord("doc-old", DocumentStatus.PROCESSED, "old", null));
            provider.graphStore().saveEntity(new GraphStore.EntityRecord("entity-old", "Old", "type", "Old", List.of(), List.of()));
            provider.graphStore().saveRelation(new GraphStore.RelationRecord("relation-old", "entity-old", "entity-old", "self", "Old", 0.1d, List.of()));
            provider.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-old:0", List.of(0.0d, 1.0d, 0.0d))));

            provider.restore(replacement);

            assertThat(provider.documentStore().list()).containsExactlyElementsOf(replacement.documents());
            assertThat(provider.chunkStore().list()).containsExactlyElementsOf(replacement.chunks());
            assertThat(provider.documentStatusStore().list()).containsExactlyElementsOf(replacement.documentStatuses());
            assertThat(provider.graphStore().allEntities()).containsExactlyElementsOf(replacement.entities());
            assertThat(provider.graphStore().allRelations()).containsExactlyElementsOf(replacement.relations());
            assertThat(provider.vectorStore().list("chunks")).containsExactlyElementsOf(replacement.vectors().get("chunks"));

            var hybridStore = (HybridVectorStore) provider.vectorStore();
            assertThat(awaitMatches(() -> hybridStore.search(
                "chunks",
                new HybridVectorStore.SearchRequest(
                    List.of(1.0d, 0.0d, 0.0d),
                    "",
                    List.of(),
                    HybridVectorStore.SearchMode.SEMANTIC,
                    1
                )
            )))
                .extracting(VectorStore.VectorMatch::id)
                .containsExactly("doc-restore:0");
            assertThat(awaitMatches(() -> hybridStore.search(
                "chunks",
                new HybridVectorStore.SearchRequest(
                    List.of(),
                    "official-sdk",
                    List.of("doc-restore"),
                    HybridVectorStore.SearchMode.KEYWORD,
                    1
                )
            )))
                .extracting(VectorStore.VectorMatch::id)
                .containsExactly("doc-restore:0");
            assertThat(awaitMatches(() -> hybridStore.search(
                "chunks",
                new HybridVectorStore.SearchRequest(
                    List.of(1.0d, 0.0d, 0.0d),
                    "official sdk hybrid",
                    List.of("official-sdk"),
                    HybridVectorStore.SearchMode.HYBRID,
                    1
                )
            )))
                .extracting(VectorStore.VectorMatch::id)
                .containsExactly("doc-restore:0");
        }
    }

    private static MySqlMilvusNeo4jStorageProvider newProvider(String workspaceId) {
        var logicalId = UUID.randomUUID().toString().replace("-", "");
        return new MySqlMilvusNeo4jStorageProvider(
            new MySqlStorageConfig(
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                "rag_" + logicalId + "_"
            ),
            new MilvusVectorConfig(
                "http://" + MILVUS.getHost() + ":" + MILVUS.getMappedPort(19530),
                "root:Milvus",
                null,
                null,
                "default",
                "rag_" + logicalId + "_",
                3
            ),
            new Neo4jGraphConfig(
                NEO4J.getBoltUrl(),
                "neo4j",
                NEO4J.getAdminPassword(),
                "neo4j"
            ),
            new InMemorySnapshotStore(),
            new WorkspaceScope(workspaceId)
        );
    }

    private static String uniqueScopeId() {
        return "ws_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static List<VectorStore.VectorMatch> awaitMatches(
        Supplier<List<VectorStore.VectorMatch>> search
    ) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        List<VectorStore.VectorMatch> last = List.of();
        while (System.nanoTime() < deadline) {
            last = search.get();
            if (!last.isEmpty()) {
                return last;
            }
            Thread.sleep(500L);
        }
        return last;
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        @Override
        public void save(java.nio.file.Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(java.nio.file.Path path) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<java.nio.file.Path> list() {
            return List.of();
        }
    }
}
