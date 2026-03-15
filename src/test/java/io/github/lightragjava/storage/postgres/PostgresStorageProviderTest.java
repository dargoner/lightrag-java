package io.github.lightragjava.storage.postgres;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresStorageProviderTest {
    @Test
    @DisplayName("bootstraps the PostgreSQL schema and required tables")
    void bootstrapsSchemaAndRequiredTables() throws SQLException {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        PostgresStorageConfig config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        SnapshotStore snapshotStore = new InMemorySnapshotStore();

        try (
            container;
            PostgresStorageProvider first = new PostgresStorageProvider(config, snapshotStore);
            PostgresStorageProvider second = new PostgresStorageProvider(config, snapshotStore)
        ) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();

            try (var connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )) {
                assertThat(existingTables(connection, config.schema())).containsExactlyInAnyOrder(
                    "rag_documents",
                    "rag_chunks",
                    "rag_entities",
                    "rag_entity_aliases",
                    "rag_entity_chunks",
                    "rag_relations",
                    "rag_relation_chunks",
                    "rag_vectors"
                );
            }
        }
    }

    @Test
    void rejectsVectorDimensionDriftOnExistingSchema() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        SnapshotStore snapshotStore = new InMemorySnapshotStore();
        PostgresStorageConfig original = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        PostgresStorageConfig drifted = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            4,
            "rag_"
        );

        try (container; PostgresStorageProvider ignored = new PostgresStorageProvider(original, snapshotStore)) {
            assertThatThrownBy(() -> new PostgresStorageProvider(drifted, snapshotStore))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector dimensions");
        }
    }

    @Test
    void rollsBackBootstrapWhenALaterStatementFails() throws SQLException {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        PostgresStorageConfig config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        try (container; HikariDataSource dataSource = newDataSource(config)) {
            PostgresSchemaManager manager = new PostgresSchemaManager(
                dataSource,
                config,
                List.of(
                    "CREATE SCHEMA IF NOT EXISTS " + config.schemaName(),
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY
                    )
                    """.formatted(config.qualifiedTableName("documents")),
                    "SELECT missing_function()"
                )
            );

            assertThatThrownBy(manager::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");

            try (var connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )) {
                assertThat(existingTables(connection, config.schema())).isEmpty();
            }
        }
    }

    @Test
    void rollsBackBootstrapWhenVectorValidationFails() throws SQLException {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        PostgresStorageConfig config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            4,
            "rag_"
        );

        try (container; HikariDataSource dataSource = newDataSource(config)) {
            PostgresSchemaManager manager = new PostgresSchemaManager(
                dataSource,
                config,
                List.of(
                    "CREATE EXTENSION IF NOT EXISTS vector",
                    "CREATE SCHEMA IF NOT EXISTS " + config.schemaName(),
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY
                    )
                    """.formatted(config.qualifiedTableName("documents")),
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        namespace TEXT NOT NULL,
                        vector_id TEXT NOT NULL,
                        embedding vector(3) NOT NULL,
                        PRIMARY KEY (namespace, vector_id)
                    )
                    """.formatted(config.qualifiedTableName("vectors"))
                )
            );

            assertThatThrownBy(manager::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector dimensions");

            try (var connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )) {
                assertThat(existingTables(connection, config.schema())).isEmpty();
            }
        }
    }

    @Test
    void commitsAllStoresWhenAtomicWriteSucceeds() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        PostgresStorageConfig config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
            provider.writeAtomically(storage -> {
                storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test")));
                storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test")));
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Alice",
                    "person",
                    "Researcher",
                    List.of("A"),
                    List.of("doc-1:0")
                ));
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-2",
                    "knows",
                    "Alice knows Bob",
                    0.9d,
                    List.of("doc-1:0")
                ));
                storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                return "ok";
            });

            assertThat(provider.documentStore().load("doc-1")).isPresent();
            assertThat(provider.chunkStore().load("doc-1:0")).isPresent();
            assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
            assertThat(provider.graphStore().loadRelation("relation-1")).isPresent();
            assertThat(provider.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));
        }
    }

    @Test
    void exposesConsistentTopLevelStoreInstances() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
            assertThat(provider.documentStore()).isSameAs(provider.documentStore());
            assertThat(provider.chunkStore()).isSameAs(provider.chunkStore());
            assertThat(provider.graphStore()).isSameAs(provider.graphStore());
            assertThat(provider.vectorStore()).isSameAs(provider.vectorStore());
            assertThat(provider.snapshotStore()).isSameAs(provider.snapshotStore());
        }
    }

    @Test
    void rollsBackAllStoresWhenAtomicWriteFails() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        var originalDocument = new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true"));
        var originalChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        var originalEntity = new GraphStore.EntityRecord(
            "entity-0",
            "Seed",
            "seed",
            "Seed entity",
            List.of("S"),
            List.of("doc-0:0")
        );
        var originalRelation = new GraphStore.RelationRecord(
            "relation-0",
            "entity-0",
            "entity-0",
            "self",
            "Seed relation",
            1.0d,
            List.of("doc-0:0")
        );
        var originalVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d));

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
            provider.documentStore().save(originalDocument);
            provider.chunkStore().save(originalChunk);
            provider.graphStore().saveEntity(originalEntity);
            provider.graphStore().saveRelation(originalRelation);
            provider.vectorStore().saveAll("chunks", List.of(originalVector));

            assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Incoming",
                    "seed",
                    "Incoming entity",
                    List.of(),
                    List.of("doc-1:0")
                ));
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-0",
                    "links_to",
                    "Incoming relation",
                    0.5d,
                    List.of("doc-1:0")
                ));
                storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d, 0.0d))));
                throw new IllegalStateException("boom");
            }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

            assertThat(provider.documentStore().list()).containsExactly(originalDocument);
            assertThat(provider.chunkStore().list()).containsExactly(originalChunk);
            assertThat(provider.graphStore().allEntities()).containsExactly(originalEntity);
            assertThat(provider.graphStore().allRelations()).containsExactly(originalRelation);
            assertThat(provider.vectorStore().list("chunks")).containsExactly(originalVector);
        }
    }

    @Test
    void restoreReplacesCurrentProviderState() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        var replacement = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Snapshot", "Body", Map.of("source", "snapshot"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "snapshot"))),
            List.of(new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A"),
                List.of("doc-1:0")
            )),
            List.of(new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("doc-1:0")
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))))
        );

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old", "Old", Map.of()));
            provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-old:0", "doc-old", "Old", 3, 0, Map.of()));
            provider.graphStore().saveEntity(new GraphStore.EntityRecord("entity-old", "Old", "type", "Old", List.of(), List.of()));
            provider.graphStore().saveRelation(new GraphStore.RelationRecord("relation-old", "entity-old", "entity-old", "self", "Old", 0.1d, List.of()));
            provider.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-old:0", List.of(0.0d, 1.0d, 0.0d))));

            provider.restore(replacement);

            assertThat(provider.documentStore().list()).containsExactlyElementsOf(replacement.documents());
            assertThat(provider.chunkStore().list()).containsExactlyElementsOf(replacement.chunks());
            assertThat(provider.graphStore().allEntities()).containsExactlyElementsOf(replacement.entities());
            assertThat(provider.graphStore().allRelations()).containsExactlyElementsOf(replacement.relations());
            assertThat(provider.vectorStore().list("chunks")).containsExactlyElementsOf(replacement.vectors().get("chunks"));
            assertThat(provider.vectorStore().list("entities")).isEmpty();
        }
    }

    @Test
    void scaffoldsDependencyBackedHarness() {
        PostgreSQLContainer<?> container = null;
        HikariDataSource dataSource = null;
        PGvector vector = null;
        PostgresStorageProvider provider = null;
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        );
    }

    private static HikariDataSource newDataSource(PostgresStorageConfig config) {
        var hikariConfig = new com.zaxxer.hikari.HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static List<String> existingTables(java.sql.Connection connection, String schema) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
                SELECT tablename
                FROM pg_tables
                WHERE schemaname = ?
                ORDER BY tablename
                """
        )) {
            statement.setString(1, schema);
            try (var resultSet = statement.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (resultSet.next()) {
                    tables.add(resultSet.getString("tablename"));
                }
                return tables;
            }
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("Not needed for bootstrap test");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
