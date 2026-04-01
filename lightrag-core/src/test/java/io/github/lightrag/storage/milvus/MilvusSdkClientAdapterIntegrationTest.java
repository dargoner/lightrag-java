package io.github.lightrag.storage.milvus;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.lightrag.exception.StorageException;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MilvusSdkClientAdapterIntegrationTest {
    private static final String ID_FIELD = "id";
    private static final String DENSE_VECTOR_FIELD = "dense_vector";
    private static final String SEARCHABLE_TEXT_FIELD = "searchable_text";
    private static final String FULL_TEXT_FIELD = "full_text";
    private static final String SPARSE_VECTOR_FIELD = "sparse_vector";

    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName ETCD_IMAGE = DockerImageName.parse("quay.io/coreos/etcd:v3.5.25");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2024-12-18T13-15-44Z");
    private static final DockerImageName MILVUS_IMAGE = DockerImageName.parse("milvusdb/milvus:v2.6.11");

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

    @Test
    void ensureCollectionRebuildsAnalyzerSchemaWhenDefinitionDrifts() {
        var logicalId = UUID.randomUUID().toString().replace("-", "");
        var config = new MilvusVectorConfig(
            "http://" + MILVUS.getHost() + ":" + MILVUS.getMappedPort(19530),
            "root:Milvus",
            null,
            null,
            "default",
            "rag_" + logicalId + "_",
            3,
            "chinese",
            "rrf",
            60,
            MilvusVectorConfig.SchemaDriftStrategy.STRICT_REBUILD
        );
        var collectionName = config.collectionName("alpha", "chunks");

        var client = new MilvusClientV2(connectConfig(config));
        try (var adapter = new MilvusSdkClientAdapter(config)) {
            dropCollectionIfExists(client, config, collectionName);
            createHybridCollection(client, config, collectionName, 3, "english");
            upsertSeedRow(client, config, collectionName);

            adapter.ensureCollection(new MilvusClientAdapter.CollectionDefinition(collectionName, 3, "chinese"));

            var description = client.describeCollection(DescribeCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(collectionName)
                .build());
            var fullTextField = description.getCollectionSchema().getField(FULL_TEXT_FIELD);

            assertThat(fullTextField.getAnalyzerParams()).containsEntry("type", "chinese");
            assertThat(adapter.list(collectionName)).isEmpty();
        } finally {
            client.close();
        }
    }

    @Test
    void ensureCollectionFailsWhenSchemaDefinitionDriftsByDefault() {
        var logicalId = UUID.randomUUID().toString().replace("-", "");
        var config = new MilvusVectorConfig(
            "http://" + MILVUS.getHost() + ":" + MILVUS.getMappedPort(19530),
            "root:Milvus",
            null,
            null,
            "default",
            "rag_" + logicalId + "_",
            3
        );
        var collectionName = config.collectionName("alpha", "chunks");

        var client = new MilvusClientV2(connectConfig(config));
        try (var adapter = new MilvusSdkClientAdapter(config)) {
            dropCollectionIfExists(client, config, collectionName);
            createHybridCollection(client, config, collectionName, 3, "english");
            upsertSeedRow(client, config, collectionName);

            assertThatThrownBy(() -> adapter.ensureCollection(
                new MilvusClientAdapter.CollectionDefinition(collectionName, 3, "chinese")
            ))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("schema drift");

            var description = client.describeCollection(DescribeCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(collectionName)
                .build());
            var fullTextField = description.getCollectionSchema().getField(FULL_TEXT_FIELD);

            assertThat(fullTextField.getAnalyzerParams()).containsEntry("type", "english");
            assertThat(adapter.list(collectionName)).hasSize(1);
        } finally {
            client.close();
        }
    }

    @Test
    void ensureCollectionIgnoresSchemaDefinitionDriftWhenConfigured() {
        var logicalId = UUID.randomUUID().toString().replace("-", "");
        var config = new MilvusVectorConfig(
            "http://" + MILVUS.getHost() + ":" + MILVUS.getMappedPort(19530),
            "root:Milvus",
            null,
            null,
            "default",
            "rag_" + logicalId + "_",
            3,
            "chinese",
            "rrf",
            60,
            MilvusVectorConfig.SchemaDriftStrategy.IGNORE
        );
        var collectionName = config.collectionName("alpha", "chunks");

        var client = new MilvusClientV2(connectConfig(config));
        try (var adapter = new MilvusSdkClientAdapter(config)) {
            dropCollectionIfExists(client, config, collectionName);
            createHybridCollection(client, config, collectionName, 3, "english");
            upsertSeedRow(client, config, collectionName);

            adapter.ensureCollection(new MilvusClientAdapter.CollectionDefinition(collectionName, 3, "chinese"));

            var description = client.describeCollection(DescribeCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(collectionName)
                .build());
            var fullTextField = description.getCollectionSchema().getField(FULL_TEXT_FIELD);

            assertThat(fullTextField.getAnalyzerParams()).containsEntry("type", "english");
            assertThat(adapter.list(collectionName)).hasSize(1);
        } finally {
            client.close();
        }
    }

    private static void createHybridCollection(
        MilvusClientV2 client,
        MilvusVectorConfig config,
        String collectionName,
        int vectorDimensions,
        String analyzerType
    ) {
        client.createCollection(CreateCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .enableDynamicField(false)
            .consistencyLevel(ConsistencyLevel.BOUNDED)
            .collectionSchema(CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(
                    CreateCollectionReq.FieldSchema.builder()
                        .name(ID_FIELD)
                        .dataType(DataType.VarChar)
                        .maxLength(191)
                        .isPrimaryKey(true)
                        .autoID(false)
                        .build(),
                    CreateCollectionReq.FieldSchema.builder()
                        .name(DENSE_VECTOR_FIELD)
                        .dataType(DataType.FloatVector)
                        .dimension(vectorDimensions)
                        .build(),
                    CreateCollectionReq.FieldSchema.builder()
                        .name(SEARCHABLE_TEXT_FIELD)
                        .dataType(DataType.VarChar)
                        .maxLength(65_535)
                        .build(),
                    CreateCollectionReq.FieldSchema.builder()
                        .name(FULL_TEXT_FIELD)
                        .dataType(DataType.VarChar)
                        .maxLength(65_535)
                        .enableAnalyzer(true)
                        .enableMatch(true)
                        .analyzerParams(Map.of("type", analyzerType))
                        .build(),
                    CreateCollectionReq.FieldSchema.builder()
                        .name(SPARSE_VECTOR_FIELD)
                        .dataType(DataType.SparseFloatVector)
                        .build()
                ))
                .functionList(List.of(
                    CreateCollectionReq.Function.builder()
                        .name("bm25_full_text")
                        .functionType(FunctionType.BM25)
                        .inputFieldNames(List.of(FULL_TEXT_FIELD))
                        .outputFieldNames(List.of(SPARSE_VECTOR_FIELD))
                        .build()
                ))
                .build())
            .indexParams(List.of(
                IndexParam.builder()
                    .fieldName(DENSE_VECTOR_FIELD)
                    .indexName("dense_autoindex")
                    .indexType(IndexParam.IndexType.AUTOINDEX)
                    .metricType(IndexParam.MetricType.COSINE)
                    .build(),
                IndexParam.builder()
                    .fieldName(SPARSE_VECTOR_FIELD)
                    .indexName("sparse_bm25_index")
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build()
            ))
            .build());
        client.loadCollection(LoadCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .sync(true)
            .build());
    }

    private static void upsertSeedRow(MilvusClientV2 client, MilvusVectorConfig config, String collectionName) {
        var row = new JsonObject();
        row.addProperty(ID_FIELD, "seed-1");
        var dense = new JsonArray();
        dense.add(1.0d);
        dense.add(0.0d);
        dense.add(0.0d);
        row.add(DENSE_VECTOR_FIELD, dense);
        row.addProperty(SEARCHABLE_TEXT_FIELD, "seed");
        row.addProperty(FULL_TEXT_FIELD, "seed text");
        client.upsert(UpsertReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .data(List.of(row))
            .build());
        client.flush(FlushReq.builder()
            .databaseName(config.databaseName())
            .collectionNames(List.of(collectionName))
            .waitFlushedTimeoutMs(30_000L)
            .build());
    }

    private static void dropCollectionIfExists(MilvusClientV2 client, MilvusVectorConfig config, String collectionName) {
        if (Boolean.TRUE.equals(client.hasCollection(io.milvus.v2.service.collection.request.HasCollectionReq.builder()
            .databaseName(config.databaseName())
            .collectionName(collectionName)
            .build()))) {
            client.dropCollection(DropCollectionReq.builder()
                .databaseName(config.databaseName())
                .collectionName(collectionName)
                .build());
        }
    }

    private static ConnectConfig connectConfig(MilvusVectorConfig config) {
        return ConnectConfig.builder()
            .uri(config.uri())
            .dbName(config.databaseName())
            .token(config.token())
            .enablePrecheck(true)
            .build();
    }
}
