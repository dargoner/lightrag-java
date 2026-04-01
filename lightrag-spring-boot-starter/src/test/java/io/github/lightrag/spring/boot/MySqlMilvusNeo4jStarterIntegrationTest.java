package io.github.lightrag.spring.boot;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
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
class MySqlMilvusNeo4jStarterIntegrationTest {
    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName ETCD_IMAGE = DockerImageName.parse("quay.io/coreos/etcd:v3.5.25");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z");
    private static final DockerImageName MILVUS_IMAGE = DockerImageName.parse("milvusdb/milvus:v2.6.11");

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));

    @Container
    private static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
        .withAdminPassword("password");

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
        .withStartupTimeout(Duration.ofMinutes(5));

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LightRagAutoConfiguration.class))
        .withUserConfiguration(TestModelConfiguration.class)
        .withPropertyValues(
            "lightrag.storage.type=mysql_milvus_neo4j",
            "lightrag.storage.mysql.jdbc-url=" + MYSQL.getJdbcUrl(),
            "lightrag.storage.mysql.username=" + MYSQL.getUsername(),
            "lightrag.storage.mysql.password=" + MYSQL.getPassword(),
            "lightrag.storage.mysql.table-prefix=ragstarter_",
            "lightrag.storage.milvus.uri=http://" + MILVUS.getHost() + ":" + MILVUS.getMappedPort(19530),
            "lightrag.storage.milvus.token=root:Milvus",
            "lightrag.storage.milvus.database=default",
            "lightrag.storage.milvus.collection-prefix=ragstarter_",
            "lightrag.storage.milvus.vector-dimensions=3",
            "lightrag.storage.neo4j.uri=" + NEO4J.getBoltUrl(),
            "lightrag.storage.neo4j.username=neo4j",
            "lightrag.storage.neo4j.password=" + NEO4J.getAdminPassword(),
            "lightrag.storage.neo4j.database=neo4j"
        );

    @Test
    void autoConfiguredWorkspaceStorageSupportsMySqlMilvusNeo4jBackends() throws Exception {
        contextRunner.run(context -> {
            var workspaceStorage = context.getBean(WorkspaceStorageProvider.class);
            var storage = workspaceStorage.forWorkspace(new WorkspaceScope("starter-" + UUID.randomUUID().toString().replace("-", "")));

            storage.writeAtomically(view -> {
                view.documentStore().save(new io.github.lightrag.storage.DocumentStore.DocumentRecord(
                    "doc-1",
                    "Starter",
                    "Milvus hybrid retrieval",
                    Map.of("source", "starter")
                ));
                view.chunkStore().save(new io.github.lightrag.storage.ChunkStore.ChunkRecord(
                    "doc-1:0",
                    "doc-1",
                    "Milvus hybrid retrieval with starter autoconfiguration",
                    7,
                    0,
                    Map.of("source", "starter")
                ));
                view.graphStore().saveEntity(new io.github.lightrag.storage.GraphStore.EntityRecord(
                    "entity-1",
                    "Milvus",
                    "database",
                    "Starter entity",
                    List.of("starter"),
                    List.of("doc-1:0")
                ));
                view.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord(
                    "doc-1:0",
                    List.of(1.0d, 0.0d, 0.0d)
                )));
                return null;
            });

            assertThat(storage.documentStore().load("doc-1")).isPresent();
            assertThat(storage.chunkStore().load("doc-1:0")).isPresent();
            assertThat(storage.graphStore().loadEntity("entity-1")).isPresent();

            var hybridStore = (HybridVectorStore) storage.vectorStore();
            assertThat(awaitMatches(() -> hybridStore.search(
                "chunks",
                new HybridVectorStore.SearchRequest(
                    List.of(1.0d, 0.0d, 0.0d),
                    "starter autoconfiguration",
                    List.of("starter"),
                    HybridVectorStore.SearchMode.HYBRID,
                    1
                )
            )))
                .extracting(VectorStore.VectorMatch::id)
                .containsExactly("doc-1:0");
        });
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

    @TestConfiguration
    static class TestModelConfiguration {
        @Bean
        ChatModel chatModel() {
            return request -> "{}";
        }

        @Bean
        EmbeddingModel embeddingModel() {
            return texts -> texts.stream()
                .map(text -> List.of(1.0d, 0.0d, 0.0d))
                .toList();
        }
    }
}
