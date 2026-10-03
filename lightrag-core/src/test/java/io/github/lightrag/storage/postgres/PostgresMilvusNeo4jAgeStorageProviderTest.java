package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore.EntityRecord;
import io.github.lightrag.storage.GraphStore.RelationRecord;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Testcontainers
class PostgresMilvusNeo4jAgeStorageProviderTest {
    private static final String MILVUS_URI = "http://localhost:19530";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newAgeContainer();

    @Test
    void roundTripsGraphWritesThroughTheAgeProjectionAndKeepsTheRelationalMirror() throws SQLException {
        assumeTrue(milvusReachable(), "Milvus is not reachable at " + MILVUS_URI);
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                milvusConfig(),
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                PostgresGraphBackend.AGE
            )) {
                provider.writeAtomically(storage -> {
                    storage.graphStore().saveEntity(alice());
                    storage.graphStore().saveEntity(bob());
                    storage.graphStore().saveRelation(aliceKnowsBob());
                    return null;
                });

                assertThat(provider.graphStore().loadEntity(alice().id())).contains(alice());
                assertThat(provider.graphStore().allEntities()).containsExactlyInAnyOrder(alice(), bob());
                assertThat(provider.graphStore().findRelations(alice().id())).containsExactly(aliceKnowsBob());

                provider.graphStore().saveEntity(carol());
                assertThat(provider.graphStore().loadEntity(carol().id())).contains(carol());

                assertThat(entityIds(dataSource, config, "default"))
                    .containsExactlyInAnyOrder(alice().id(), bob().id(), carol().id());
            }
        }
    }

    @Test
    void assemblesProviderFromConfigsWithoutNeo4jConfiguration() throws SQLException {
        assumeTrue(milvusReachable(), "Milvus is not reachable at " + MILVUS_URI);
        var config = newConfig();
        try (var provider = new PostgresMilvusNeo4jStorageProvider(
            config,
            milvusConfig(),
            new InMemorySnapshotStore(),
            PostgresGraphBackend.AGE
        )) {
            provider.writeAtomically(storage -> {
                storage.graphStore().saveEntity(alice());
                return null;
            });

            assertThat(provider.graphStore().loadEntity(alice().id())).contains(alice());

            try (var dataSource = newDataSource(config)) {
                assertThat(entityIds(dataSource, config, "default")).contains(alice().id());
            }
        }
    }

    @Test
    void rollsBackTheAgeProjectionWhenALaterApplyStepFails() throws SQLException {
        var config = newConfig();
        var workspace = new WorkspaceScope("ws_" + UUID.randomUUID().toString().replace("-", ""));
        try (var dataSource = newDataSource(config)) {
            new PostgresAgeBootstrap(dataSource, workspace.workspaceId()).bootstrap();
            try (var graphAdapter = new PostgresAgeGraphStorageAdapter(dataSource, workspace.workspaceId());
                 var provider = new PostgresMilvusNeo4jStorageProvider(
                     dataSource,
                     config,
                     new InMemorySnapshotStore(),
                     workspace,
                     graphAdapter,
                     new FailingVectorAdapter()
                 )) {
                provider.writeAtomically(storage -> {
                    storage.graphStore().saveEntity(alice());
                    return null;
                });
                assertThat(graphAdapter.captureSnapshot().entities()).containsExactly(alice());

                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.graphStore().saveEntity(updatedAlice());
                    storage.graphStore().saveEntity(carol());
                    storage.vectorStore().saveAll(
                        "chunks",
                        List.of(new VectorStore.VectorRecord("vector-1", List.of(0.1d, 0.2d)))
                    );
                    return null;
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("vector apply failed");

                assertThat(graphAdapter.captureSnapshot().entities()).containsExactly(alice());
                assertThat(entityIds(dataSource, config, workspace.workspaceId()))
                    .containsExactly(alice().id());
            }
        }
    }

    private static EntityRecord alice() {
        return new EntityRecord(
            "entity-alice",
            "Alice",
            "person",
            "Alice description",
            List.of("Al"),
            List.of("chunk-1")
        );
    }

    private static EntityRecord updatedAlice() {
        return new EntityRecord(
            "entity-alice",
            "Alice Smith",
            "researcher",
            "updated description",
            List.of("Al", "Alice"),
            List.of("chunk-2")
        );
    }

    private static EntityRecord bob() {
        return new EntityRecord("entity-bob", "Bob", "person", "Bob description", List.of(), List.of("chunk-1"));
    }

    private static EntityRecord carol() {
        return new EntityRecord("entity-carol", "Carol", "person", "Carol description", List.of(), List.of("chunk-3"));
    }

    private static RelationRecord aliceKnowsBob() {
        return new RelationRecord(
            "relation-alice-bob",
            "entity-alice",
            "entity-bob",
            "knows",
            "Alice knows Bob",
            0.75d,
            "chunk-1<SEP>chunk-2",
            "/docs/alice.md"
        );
    }

    private static PostgreSQLContainer<?> newAgeContainer() {
        var image = DockerImageName.parse(
            System.getenv().getOrDefault("LIGHTRAG_AGE_IMAGE", "apache/age:release_PG16_1.6.0")
        ).asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static PostgresStorageConfig newConfig() {
        var schema = "lightrag_" + UUID.randomUUID().toString().replace("-", "");
        return new PostgresStorageConfig(
            withCurrentSchema(POSTGRES.getJdbcUrl(), schema),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            schema,
            3,
            "rag_"
        );
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }

    private static MilvusVectorConfig milvusConfig() {
        return new MilvusVectorConfig(MILVUS_URI, "root:Milvus", null, null, "default", "rag_", 3);
    }

    private static boolean milvusReachable() {
        var uri = URI.create(MILVUS_URI);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 1000);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static HikariDataSource newDataSource(PostgresStorageConfig config) {
        createSchema(config.schemaName());
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    /**
     * The relational adapter aligns the config schema with the data source's current schema when the data source is
     * external, so the schema behind the {@code currentSchema} parameter must exist before the provider is built.
     */
    private static void createSchema(String schema) {
        try (var connection = java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create test schema " + schema, exception);
        }
    }

    private static List<String> entityIds(HikariDataSource dataSource, PostgresStorageConfig config, String workspaceId)
        throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT id FROM " + config.qualifiedTableName("entities") + " WHERE workspace_id = ?"
             )) {
            statement.setString(1, workspaceId);
            try (var resultSet = statement.executeQuery()) {
                var ids = new ArrayList<String>();
                while (resultSet.next()) {
                    ids.add(resultSet.getString(1));
                }
                return ids;
            }
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        private final Map<Path, Snapshot> snapshots = new LinkedHashMap<>();

        @Override
        public void save(Path path, Snapshot snapshot) {
            snapshots.put(path, snapshot);
        }

        @Override
        public Snapshot load(Path path) {
            return snapshots.get(path);
        }

        @Override
        public List<Path> list() {
            return snapshots.keySet().stream().toList();
        }
    }

    private static final class FailingVectorAdapter implements VectorStorageAdapter {
        private final VectorStore vectorStore = new VectorStore() {
            @Override
            public void saveAll(String namespace, List<VectorRecord> vectors) {
            }

            @Override
            public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
                return List.of();
            }

            @Override
            public List<VectorRecord> list(String namespace) {
                return List.of();
            }
        };

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            return VectorSnapshot.empty();
        }

        @Override
        public void apply(StagedVectorWrites writes) {
            throw new IllegalStateException("vector apply failed");
        }

        @Override
        public void restore(VectorSnapshot snapshot) {
        }
    }
}
