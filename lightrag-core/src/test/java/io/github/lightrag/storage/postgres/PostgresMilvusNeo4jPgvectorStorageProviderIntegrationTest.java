package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageLockManager;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Family-mode integration test: {@link PostgresMilvusNeo4jStorageProvider} with the AGE graph and
 * pgvector vectors on the same PostgreSQL data source. Disabled unless {@code LIGHTRAG_POSTGRES_AGE_IT=true}
 * is set (environment variable, or system property for local runs).
 *
 * <p>Without {@code LIGHTRAG_POSTGRES_AGE_IMAGE} the test builds its image from
 * {@code src/test/resources/postgres-age/Dockerfile}; the build needs network access on first run.</p>
 */
class PostgresMilvusNeo4jPgvectorStorageProviderIntegrationTest {
    @Test
    void roundTripsDocumentsGraphAndVectorsThroughTheFamilyProvider() throws SQLException {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var dataSource = newDataSource(container, config);
                 var provider = newFamilyProvider(dataSource, config)) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-1", "doc-1", "Body", 1, 0, Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-2", "doc-1", "Body", 1, 1, Map.of()));
                    storage.graphStore().saveEntity(alice());
                    storage.graphStore().saveEntity(bob());
                    storage.graphStore().saveRelation(aliceKnowsBob());
                    ((HybridVectorStore) storage.vectorStore()).saveAllEnriched("chunks", List.of(
                        new HybridVectorStore.EnrichedVectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d), "first body", List.of("first")),
                        new HybridVectorStore.EnrichedVectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d), "second body", List.of("second"))
                    ));
                    storage.vectorStore().saveAll("entities", List.of(
                        new VectorStore.VectorRecord("entity-alice", List.of(0.0d, 0.0d, 1.0d))
                    ));
                    return null;
                });

                assertThat(provider.documentStore().load("doc-1")).isPresent();
                assertThat(provider.graphStore().loadEntity("entity-alice")).contains(alice());
                assertThat(provider.graphStore().loadRelation("relation-alice-bob")).contains(aliceKnowsBob());

                assertThat(provider.vectorStore().search("chunks", List.of(1.0d, 0.0d, 0.0d), 3))
                    .extracting(VectorStore.VectorMatch::id)
                    .containsExactly("chunk-1", "chunk-2");
                assertThat(provider.vectorStore().list("entities"))
                    .containsExactly(new VectorStore.VectorRecord("entity-alice", List.of(0.0d, 0.0d, 1.0d)));

                // The delegate is a plain pgvector store: hybrid and keyword requests must degrade
                // instead of failing, returning dense results (or nothing without a query vector).
                var hybridStore = (HybridVectorStore) provider.vectorStore();
                assertThat(hybridStore.search("chunks", new HybridVectorStore.SearchRequest(
                    List.of(1.0d, 0.0d, 0.0d), "first body", List.of("first"), HybridVectorStore.SearchMode.HYBRID, 3
                ))).extracting(VectorStore.VectorMatch::id).containsExactly("chunk-1", "chunk-2");
                assertThat(hybridStore.search("chunks", new HybridVectorStore.SearchRequest(
                    List.of(), "first body", List.of("first"), HybridVectorStore.SearchMode.KEYWORD, 3
                ))).isEmpty();

                assertThat(vectorIds(dataSource, config, "chunks")).containsExactly("chunk-1", "chunk-2");
                assertThat(vectorIds(dataSource, config, "entities")).containsExactly("entity-alice");
            }
        }
    }

    @Test
    void transactionalVectorWritesRollBackWithTheRelationalTransaction() throws SQLException {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var dataSource = newDataSource(container, config);
                 var provider = newFamilyProvider(dataSource, config)) {
                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-rollback", "Title", "Body", Map.of()));
                    storage.vectorStore().saveAll("chunks", List.of(
                        new VectorStore.VectorRecord("chunk-rollback", List.of(1.0d, 0.0d, 0.0d))
                    ));
                    throw new IllegalStateException("rollback requested by the test");
                })).isInstanceOf(IllegalStateException.class);

                assertThat(provider.documentStore().list()).isEmpty();
                assertThat(provider.vectorStore().list("chunks")).isEmpty();
                assertThat(vectorIds(dataSource, config, "chunks")).isEmpty();
            }
        }
    }

    @Test
    void deleteDocumentDerivedStateRemovesVectorsFromAllNamespaces() throws SQLException {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var dataSource = newDataSource(container, config);
                 var provider = newFamilyProvider(dataSource, config)) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-1", "doc-1", "First body", 1, 0, Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-2", "doc-1", "Second body", 1, 1, Map.of()));
                    storage.graphStore().saveEntity(alice());
                    storage.graphStore().saveEntity(bob());
                    storage.graphStore().saveRelation(aliceKnowsBob());
                    storage.vectorStore().saveAll("chunks", List.of(
                        new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)),
                        new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
                    ));
                    storage.vectorStore().saveAll("entities", List.of(
                        new VectorStore.VectorRecord("entity-alice", List.of(0.0d, 0.0d, 1.0d)),
                        new VectorStore.VectorRecord("entity-bob", List.of(0.0d, 0.0d, 1.0d))
                    ));
                    storage.vectorStore().saveAll("relations", List.of(
                        new VectorStore.VectorRecord("relation-alice-bob", List.of(0.5d, 0.5d, 0.0d))
                    ));
                    return null;
                });

                var result = provider.deleteDocumentDerivedState("doc-1", List.of("chunk-1"));

                assertThat(result.vectorNamespacesTouched()).isEqualTo(3);
                assertThat(result.entitiesDeleted()).isEqualTo(1);
                assertThat(result.relationsDeleted()).isEqualTo(1);
                assertThat(provider.graphStore().loadEntity("entity-alice")).isEmpty();
                assertThat(provider.graphStore().loadEntity("entity-bob")).contains(bob());
                assertThat(provider.graphStore().loadRelation("relation-alice-bob")).isEmpty();

                assertThat(vectorIds(dataSource, config, "chunks")).containsExactly("chunk-2");
                assertThat(vectorIds(dataSource, config, "entities")).containsExactly("entity-bob");
                assertThat(vectorIds(dataSource, config, "relations")).isEmpty();
            }
        }
    }

    @Test
    void restoreReplacesVectorNamespacesInsteadOfAppending() throws SQLException {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var dataSource = newDataSource(container, config);
                 var provider = newFamilyProvider(dataSource, config)) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("chunk-1", "doc-1", "Body", 1, 0, Map.of()));
                    storage.graphStore().saveEntity(alice());
                    storage.vectorStore().saveAll("chunks", List.of(
                        new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d))
                    ));
                    storage.vectorStore().saveAll("entities", List.of(
                        new VectorStore.VectorRecord("entity-alice", List.of(0.0d, 0.0d, 1.0d))
                    ));
                    return null;
                });

                var snapshot = StorageSnapshots.capture(provider);
                provider.vectorStore().saveAll("chunks", List.of(
                    new VectorStore.VectorRecord("chunk-stale", List.of(0.0d, 1.0d, 0.0d))
                ));
                assertThat(vectorIds(dataSource, config, "chunks")).containsExactly("chunk-1", "chunk-stale");

                provider.restore(snapshot);

                assertThat(vectorIds(dataSource, config, "chunks")).containsExactly("chunk-1");
                assertThat(provider.vectorStore().list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)));
                assertThat(provider.vectorStore().list("entities"))
                    .containsExactly(new VectorStore.VectorRecord("entity-alice", List.of(0.0d, 0.0d, 1.0d)));
                assertThat(provider.documentStore().load("doc-1")).isPresent();
                assertThat(provider.graphStore().loadEntity("entity-alice")).contains(alice());
            }
        }
    }

    private static PostgresMilvusNeo4jStorageProvider newFamilyProvider(
        HikariDataSource dataSource,
        PostgresStorageConfig config
    ) {
        return new PostgresMilvusNeo4jStorageProvider(
            dataSource,
            config,
            new InMemorySnapshotStore(),
            new WorkspaceScope("default"),
            PostgresGraphBackend.AGE,
            StorageLockManager.noop()
        );
    }

    private static GraphStore.EntityRecord alice() {
        return new GraphStore.EntityRecord(
            "entity-alice",
            "Alice",
            "person",
            "Alice description",
            List.of("Al"),
            List.of("chunk-1")
        );
    }

    private static GraphStore.EntityRecord bob() {
        return new GraphStore.EntityRecord(
            "entity-bob",
            "Bob",
            "person",
            "Bob description",
            List.of(),
            List.of("chunk-2")
        );
    }

    private static GraphStore.RelationRecord aliceKnowsBob() {
        return new GraphStore.RelationRecord(
            "relation-alice-bob",
            "entity-alice",
            "entity-bob",
            "knows",
            "Alice knows Bob",
            0.75d,
            List.of("chunk-1", "chunk-2")
        );
    }

    private static PostgreSQLContainer<?> startAgeContainer() {
        assumeTrue(ageIntegrationTestsEnabled(), "LIGHTRAG_POSTGRES_AGE_IT is not enabled");
        var imageOverride = System.getenv("LIGHTRAG_POSTGRES_AGE_IMAGE");
        PostgreSQLContainer<?> container;
        if (imageOverride == null || imageOverride.isBlank()) {
            container = new PostgreSQLContainer<>(DockerImageName.parse(buildAgePgVectorImage()).asCompatibleSubstituteFor("postgres"));
        } else {
            container = new PostgreSQLContainer<>(DockerImageName.parse(imageOverride).asCompatibleSubstituteFor("postgres"));
        }
        container.start();
        return container;
    }

    private static String buildAgePgVectorImage() {
        // ImageFromDockerfile builds lazily and caches the image under this name between runs.
        return new ImageFromDockerfile("postgres-age-pgvector:pg16", false)
            .withFileFromClasspath("Dockerfile", "postgres-age/Dockerfile")
            .get();
    }

    private static boolean ageIntegrationTestsEnabled() {
        var value = System.getenv("LIGHTRAG_POSTGRES_AGE_IT");
        if (value == null) {
            value = System.getProperty("LIGHTRAG_POSTGRES_AGE_IT");
        }
        return Boolean.parseBoolean(value);
    }

    private static PostgresStorageConfig newConfig(PostgreSQLContainer<?> container) {
        var schema = "lightrag_" + UUID.randomUUID().toString().replace("-", "");
        return new PostgresStorageConfig(
            withCurrentSchema(container.getJdbcUrl(), schema),
            container.getUsername(),
            container.getPassword(),
            schema,
            3,
            "rag_"
        );
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }

    /**
     * The relational adapter aligns the config schema with the data source's current schema when the
     * data source is external, so the schema behind the {@code currentSchema} parameter must exist first.
     */
    private static HikariDataSource newDataSource(PostgreSQLContainer<?> container, PostgresStorageConfig config) {
        createSchema(container, config.schemaName());
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static void createSchema(PostgreSQLContainer<?> container, String schema) {
        try (var connection = DriverManager.getConnection(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to create test schema " + schema, exception);
        }
    }

    private static List<String> vectorIds(HikariDataSource dataSource, PostgresStorageConfig config, String namespace)
        throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT vector_id FROM " + config.qualifiedTableName("vectors")
                     + " WHERE workspace_id = ? AND namespace = ? ORDER BY vector_id"
             )) {
            statement.setString(1, "default");
            statement.setString(2, namespace);
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
}
