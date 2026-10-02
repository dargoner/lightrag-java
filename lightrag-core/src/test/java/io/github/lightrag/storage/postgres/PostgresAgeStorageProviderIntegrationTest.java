package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Provider-level Apache AGE integration test: the full {@link PostgresStorageProvider} against
 * PostgreSQL with both Apache AGE and pgvector. Disabled unless {@code LIGHTRAG_POSTGRES_AGE_IT=true}
 * is set (environment variable, or system property for local runs).
 *
 * <p>Without {@code LIGHTRAG_POSTGRES_AGE_IMAGE} the test builds its image from
 * {@code src/test/resources/postgres-age/Dockerfile}; the build needs network access on first run.</p>
 */
class PostgresAgeStorageProviderIntegrationTest {
    @Test
    void providerWithAgeBackendRoundTripsTheGraphThroughAtomicWrites() {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var provider = new PostgresStorageProvider(
                config,
                new InMemorySnapshotStore(),
                PostgresGraphBackend.AGE
            )) {
                var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
                var bob = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of("chunk-1"));
                var relation = new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-2",
                    "knows",
                    "Alice knows Bob",
                    0.9d,
                    List.of("chunk-1")
                );

                provider.writeAtomically(view -> {
                    view.graphStore().saveEntity(alice);
                    view.graphStore().saveEntity(bob);
                    view.graphStore().saveRelation(relation);
                    return "committed";
                });

                assertThat(provider.graphStore().loadEntity("entity-1")).contains(alice);
                assertThat(provider.graphStore().loadEntity("entity-2")).contains(bob);
                assertThat(provider.graphStore().loadRelation("relation-1")).contains(relation);
                assertThat(provider.graphStore().allEntities()).containsExactly(alice, bob);
                assertThat(provider.graphStore().allRelations()).containsExactly(relation);
                assertThat(provider.graphStore().labels()).containsExactly("entity-1", "entity-2");

                assertThat(queryStrings(
                    config,
                    "SELECT name::text FROM ag_catalog.ag_graph WHERE name = 'chunk_entity_relation'"
                )).containsExactly("chunk_entity_relation");
            }
        }
    }

    @Test
    void rolledBackAtomicWritesLeaveNoTraceInTheAgeGraph() {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var provider = new PostgresStorageProvider(
                config,
                new InMemorySnapshotStore(),
                PostgresGraphBackend.AGE
            )) {
                var alice = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of(), List.of());
                var bob = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of());
                var relation = new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-2",
                    "knows",
                    "Alice knows Bob",
                    0.9d,
                    List.of("chunk-1")
                );

                assertThatThrownBy(() -> provider.writeAtomically(view -> {
                    view.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
                    view.graphStore().saveEntity(alice);
                    view.graphStore().saveEntity(bob);
                    view.graphStore().saveRelation(relation);
                    throw new IllegalStateException("rollback requested by the test");
                })).isInstanceOf(IllegalStateException.class);

                assertThat(provider.documentStore().list()).isEmpty();
                assertThat(provider.graphStore().allEntities()).isEmpty();
                assertThat(provider.graphStore().allRelations()).isEmpty();
            }
        }
    }

    @Test
    void restoreReplacesThenClearsTheAgeGraph() {
        try (var container = startAgeContainer()) {
            var config = newConfig(container);
            try (var provider = new PostgresStorageProvider(
                config,
                new InMemorySnapshotStore(),
                PostgresGraphBackend.AGE
            )) {
                var snapshot = new SnapshotStore.Snapshot(
                    List.of(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "snapshot"))),
                    List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "snapshot"))),
                    List.of(
                        new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("doc-1:0")),
                        new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of("doc-1:0"))
                    ),
                    List.of(new GraphStore.RelationRecord(
                        "relation-1",
                        "entity-1",
                        "entity-2",
                        "knows",
                        "Alice knows Bob",
                        0.9d,
                        List.of("doc-1:0")
                    )),
                    Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)))),
                    List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "snapshot", null))
                );

                var stale = new GraphStore.EntityRecord("entity-old", "Old", "person", "stale", List.of(), List.of());
                provider.writeAtomically(view -> {
                    view.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old", "stale", Map.of()));
                    view.graphStore().saveEntity(stale);
                    return null;
                });

                provider.restore(snapshot);

                assertThat(provider.documentStore().list()).containsExactlyElementsOf(snapshot.documents());
                assertThat(provider.graphStore().allEntities()).containsExactlyElementsOf(snapshot.entities());
                assertThat(provider.graphStore().allRelations()).containsExactlyElementsOf(snapshot.relations());

                var empty = new SnapshotStore.Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of(), List.of());

                provider.restore(empty);

                assertThat(provider.documentStore().list()).isEmpty();
                assertThat(provider.graphStore().allEntities()).isEmpty();
                assertThat(provider.graphStore().allRelations()).isEmpty();
            }
        }
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
        return new ImageFromDockerfile("lightrag-postgres-age-pgvector:pg16", false)
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
        return new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag_" + UUID.randomUUID().toString().replace("-", ""),
            3,
            "rag_"
        );
    }

    private static List<String> queryStrings(PostgresStorageConfig config, String sql) {
        try (var connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            var values = new ArrayList<String>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("Not needed for the AGE integration test");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
