package io.github.lightrag.storage.postgres;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
                    "rag_document_status",
                    "rag_document_graph_snapshots",
                    "rag_chunk_graph_snapshots",
                    "rag_document_graph_journals",
                    "rag_chunk_graph_journals",
                    "rag_entities",
                    "rag_entity_aliases",
                    "rag_entity_chunks",
                    "rag_relations",
                    "rag_relation_chunks",
                    "rag_schema_version",
                    "rag_task",
                    "rag_task_stage",
                    "rag_vectors"
                );
                assertThat(columnNames(connection, config, "documents")).contains("workspace_id");
                assertThat(columnNames(connection, config, "chunks")).contains("workspace_id", "document_id");
                assertThat(columnNames(connection, config, "entities")).contains("workspace_id");
                assertThat(columnNames(connection, config, "relations")).contains("workspace_id");
                assertThat(columnNames(connection, config, "vectors")).contains("workspace_id");
                assertThat(columnNames(connection, config, "document_status")).contains("workspace_id");
                assertThat(columnNames(connection, config, "task")).contains("workspace_id");
                assertThat(columnNames(connection, config, "task_stage")).contains("workspace_id");
                assertThat(primaryKeyColumns(connection, config, "documents")).containsExactly("workspace_id", "id");
                assertThat(primaryKeyColumns(connection, config, "chunks")).containsExactly("workspace_id", "id");
                assertThat(primaryKeyColumns(connection, config, "entities")).containsExactly("workspace_id", "id");
                assertThat(primaryKeyColumns(connection, config, "relations")).containsExactly("workspace_id", "id");
                assertThat(primaryKeyColumns(connection, config, "vectors"))
                    .containsExactly("workspace_id", "namespace", "vector_id");
                assertThat(primaryKeyColumns(connection, config, "document_status"))
                    .containsExactly("workspace_id", "document_id");
                assertThat(primaryKeyColumns(connection, config, "task"))
                    .containsExactly("workspace_id", "task_id");
                assertThat(primaryKeyColumns(connection, config, "task_stage"))
                    .containsExactly("workspace_id", "task_id", "stage");
                assertThat(schemaVersion(connection, config)).contains(4);
            }
        }
    }

    @Test
    @DisplayName("accepts pgvector type validation when search_path excludes public")
    void acceptsSchemaQualifiedVectorTypeWhenSearchPathExcludesPublic() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        PostgresStorageConfig bootstrapConfig = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        PostgresStorageConfig validationConfig = new PostgresStorageConfig(
            withCurrentSchema(container.getJdbcUrl(), "lightrag"),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        try (container) {
            try (PostgresStorageProvider ignored = new PostgresStorageProvider(bootstrapConfig, new InMemorySnapshotStore())) {
                assertThat(ignored).isNotNull();
            }

            try (PostgresStorageProvider provider = new PostgresStorageProvider(validationConfig, new InMemorySnapshotStore())) {
                assertThat(provider).isNotNull();
            }
        }
    }

    @Test
    void rejectsLegacySchemaWithoutWorkspaceColumns() throws SQLException {
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
            var connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )
        ) {
            createLegacySchema(connection, config);

            assertThatThrownBy(() -> new PostgresStorageProvider(config, snapshotStore))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy")
                .hasMessageContaining("workspace");
        }
    }

    @Test
    void rejectsUnsupportedNewerSchemaVersion() throws SQLException {
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
            var connection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )
        ) {
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
            connection.createStatement().execute(
                """
                CREATE TABLE IF NOT EXISTS %s (
                    schema_key TEXT PRIMARY KEY,
                    version INTEGER NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """.formatted(config.qualifiedTableName("schema_version"))
            );
            connection.createStatement().execute(
                """
                INSERT INTO %s (schema_key, version)
                VALUES ('storage', 999)
                """.formatted(config.qualifiedTableName("schema_version"))
            );

            assertThatThrownBy(() -> new PostgresStorageProvider(config, snapshotStore))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema version")
                .hasMessageContaining("999")
                .hasMessageContaining("4");
        }
    }

    @Test
    void repairsVersionedSchemaByReplayingCurrentMigration() throws SQLException {
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

        try (container) {
            try (PostgresStorageProvider ignored = new PostgresStorageProvider(config, snapshotStore)) {
                try (var connection = DriverManager.getConnection(
                    config.jdbcUrl(),
                    config.username(),
                    config.password()
                )) {
                    connection.createStatement().execute("DROP TABLE " + config.qualifiedTableName("documents"));
                    assertThat(existingTables(connection, config.schema())).doesNotContain("rag_documents");
                    assertThat(schemaVersion(connection, config)).contains(4);
                }
            }

            try (
                PostgresStorageProvider ignored = new PostgresStorageProvider(config, snapshotStore);
                var connection = DriverManager.getConnection(
                    config.jdbcUrl(),
                    config.username(),
                    config.password()
                )
            ) {
                assertThat(existingTables(connection, config.schema())).contains("rag_documents");
                assertThat(existingTables(connection, config.schema())).contains("rag_document_status");
                assertThat(existingTables(connection, config.schema())).contains("rag_task");
                assertThat(existingTables(connection, config.schema())).contains("rag_task_stage");
                assertThat(schemaVersion(connection, config)).contains(4);
            }
        }
    }

    @Test
    void isolatesAllStoresByWorkspaceIdInsideSharedTables() {
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

        try (
            container;
            PostgresStorageProvider alpha = new PostgresStorageProvider(config, new InMemorySnapshotStore(), "alpha");
            PostgresStorageProvider beta = new PostgresStorageProvider(config, new InMemorySnapshotStore(), "beta")
        ) {
            alpha.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Alpha", "alpha", Map.of("workspace", "alpha")));
            alpha.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "alpha", 5, 0, Map.of("workspace", "alpha")));
            alpha.graphStore().saveEntity(new GraphStore.EntityRecord("entity-1", "Alice", "person", "Alpha entity", List.of("A"), List.of("doc-1:0")));
            alpha.graphStore().saveRelation(new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alpha relation", 0.8d, List.of("doc-1:0")));
            alpha.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
            alpha.documentStatusStore().save(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "alpha done", null));

            beta.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Beta", "beta", Map.of("workspace", "beta")));
            beta.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "beta", 4, 0, Map.of("workspace", "beta")));
            beta.graphStore().saveEntity(new GraphStore.EntityRecord("entity-1", "Bob", "person", "Beta entity", List.of("B"), List.of("doc-1:0")));
            beta.graphStore().saveRelation(new GraphStore.RelationRecord("relation-1", "entity-1", "entity-3", "works_with", "Beta relation", 0.4d, List.of("doc-1:0")));
            beta.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
            beta.documentStatusStore().save(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.FAILED, "beta failed", "boom"));

            assertThat(alpha.documentStore().load("doc-1"))
                .contains(new DocumentStore.DocumentRecord("doc-1", "Alpha", "alpha", Map.of("workspace", "alpha")));
            assertThat(beta.documentStore().load("doc-1"))
                .contains(new DocumentStore.DocumentRecord("doc-1", "Beta", "beta", Map.of("workspace", "beta")));
            assertThat(alpha.chunkStore().list()).containsExactly(
                new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "alpha", 5, 0, Map.of("workspace", "alpha"))
            );
            assertThat(beta.chunkStore().list()).containsExactly(
                new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "beta", 4, 0, Map.of("workspace", "beta"))
            );
            assertThat(alpha.graphStore().loadEntity("entity-1")).contains(
                new GraphStore.EntityRecord("entity-1", "Alice", "person", "Alpha entity", List.of("A"), List.of("doc-1:0"))
            );
            assertThat(beta.graphStore().loadEntity("entity-1")).contains(
                new GraphStore.EntityRecord("entity-1", "Bob", "person", "Beta entity", List.of("B"), List.of("doc-1:0"))
            );
            assertThat(alpha.graphStore().loadRelation("relation-1")).contains(
                new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alpha relation", 0.8d, List.of("doc-1:0"))
            );
            assertThat(beta.graphStore().loadRelation("relation-1")).contains(
                new GraphStore.RelationRecord("relation-1", "entity-1", "entity-3", "works_with", "Beta relation", 0.4d, List.of("doc-1:0"))
            );
            assertThat(alpha.vectorStore().list("chunks")).containsExactly(
                new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))
            );
            assertThat(beta.vectorStore().list("chunks")).containsExactly(
                new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))
            );
            assertThat(alpha.documentStatusStore().load("doc-1")).contains(
                new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "alpha done", null)
            );
            assertThat(beta.documentStatusStore().load("doc-1")).contains(
                new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.FAILED, "beta failed", "boom")
            );
        }
    }

    @Test
    void restoreOnlyReplacesCurrentWorkspaceRows() {
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

        var alphaSnapshot = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Alpha Snapshot", "alpha-body", Map.of("workspace", "alpha"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "alpha-body", 10, 0, Map.of("workspace", "alpha"))),
            List.of(new GraphStore.EntityRecord("entity-1", "Alice", "person", "Alpha entity", List.of("A"), List.of("doc-1:0"))),
            List.of(new GraphStore.RelationRecord("relation-1", "entity-1", "entity-2", "knows", "Alpha relation", 1.0d, List.of("doc-1:0"))),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)))),
            List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "alpha", null))
        );

        try (
            container;
            PostgresStorageProvider alpha = new PostgresStorageProvider(config, new InMemorySnapshotStore(), "alpha");
            PostgresStorageProvider beta = new PostgresStorageProvider(config, new InMemorySnapshotStore(), "beta")
        ) {
            alpha.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old Alpha", "old", Map.of("workspace", "alpha")));
            beta.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old Beta", "old", Map.of("workspace", "beta")));

            alpha.restore(alphaSnapshot);

            assertThat(alpha.documentStore().list()).containsExactlyElementsOf(alphaSnapshot.documents());
            assertThat(alpha.chunkStore().list()).containsExactlyElementsOf(alphaSnapshot.chunks());
            assertThat(alpha.graphStore().allEntities()).containsExactlyElementsOf(alphaSnapshot.entities());
            assertThat(alpha.graphStore().allRelations()).containsExactlyElementsOf(alphaSnapshot.relations());
            assertThat(alpha.vectorStore().list("chunks")).containsExactlyElementsOf(alphaSnapshot.vectors().get("chunks"));
            assertThat(alpha.documentStatusStore().list()).containsExactlyElementsOf(alphaSnapshot.documentStatuses());
            assertThat(beta.documentStore().list()).containsExactly(
                new DocumentStore.DocumentRecord("doc-old", "Old Beta", "old", Map.of("workspace", "beta"))
            );
        }
    }

    @Test
    void supportsExternalDataSourceWithoutCreatingOwnedPools() throws Exception {
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

        try (
            container;
            HikariDataSource externalDataSource = newDataSource(config);
            PostgresStorageProvider provider = new PostgresStorageProvider(
                externalDataSource,
                config,
                new InMemorySnapshotStore(),
                "alpha"
            )
        ) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "external")));

            assertThat(provider.documentStore().load("doc-1"))
                .contains(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "external")));
            assertThat(readField(provider, "jdbcDataSource")).isSameAs(externalDataSource);
            assertThat(readField(provider, "jdbcLockDataSource")).isSameAs(externalDataSource);
        }
    }

    @Test
    void doesNotCloseExternalDataSourceOnProviderClose() throws Exception {
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

        try (
            container;
            HikariDataSource externalDataSource = newDataSource(config)
        ) {
            var provider = new PostgresStorageProvider(
                externalDataSource,
                config,
                new InMemorySnapshotStore(),
                "alpha"
            );

            provider.close();

            try (var connection = externalDataSource.getConnection();
                 var statement = connection.prepareStatement("SELECT 1");
                 var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }

        }
    }

    @Test
    void alignsSchemaWithExternalDataSourceCurrentSchema() throws Exception {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();

        var declaredConfig = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        var externalDataSourceConfig = new PostgresStorageConfig(
            withCurrentSchema(container.getJdbcUrl(), "public"),
            container.getUsername(),
            container.getPassword(),
            "public",
            3,
            "rag_"
        );

        try (
            container;
            HikariDataSource externalDataSource = newDataSource(externalDataSourceConfig);
            PostgresStorageProvider provider = new PostgresStorageProvider(
                externalDataSource,
                declaredConfig,
                new InMemorySnapshotStore(),
                "alpha"
            )
        ) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "external")));

            var effectiveConfig = (PostgresStorageConfig) readField(provider, "config");
            assertThat(effectiveConfig.schema()).isEqualTo("public");
            try (var connection = externalDataSource.getConnection()) {
                assertThat(existingTables(connection, "public")).contains("rag_documents");
                assertThat(existingTables(connection, "lightrag")).doesNotContain("rag_documents");
            }
        }
    }

    @Test
    void persistsDocumentStatusesAcrossProviderInstances() {
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

        try (
            container;
            PostgresStorageProvider writer = new PostgresStorageProvider(config, new InMemorySnapshotStore());
            PostgresStorageProvider reader = new PostgresStorageProvider(config, new InMemorySnapshotStore())
        ) {
            writer.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                "doc-1",
                DocumentStatus.PROCESSED,
                "processed 1 chunks",
                null
            ));

            assertThat(reader.documentStatusStore().load("doc-1"))
                .contains(new DocumentStatusStore.StatusRecord(
                    "doc-1",
                    DocumentStatus.PROCESSED,
                    "processed 1 chunks",
                    null
                ));
            assertThat(reader.documentStatusStore().list())
                .containsExactly(new DocumentStatusStore.StatusRecord(
                    "doc-1",
                    DocumentStatus.PROCESSED,
                    "processed 1 chunks",
                    null
                ));
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
    void retriesAtomicWriteWhenTransactionConflictsAreTransient() {
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
            var attempts = new AtomicInteger();

            provider.writeAtomically(storage -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new StorageException(
                        "transient serialization conflict",
                        new SQLTransactionRollbackException("serialization failure", "40001")
                    );
                }
                storage.documentStore().save(new DocumentStore.DocumentRecord("doc-retry", "Retry", "ok", Map.of()));
                return null;
            });

            assertThat(attempts).hasValue(3);
            assertThat(provider.documentStore().load("doc-retry")).isPresent();
        }
    }

    @Test
    void exposesConsistentTopLevelStoreInstances() {
        PostgreSQLContainer<?> container = newPostgresContainer();
        container.start();
        var snapshotStore = new InMemorySnapshotStore();

        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, snapshotStore)) {
            assertThat(provider.documentStore()).isSameAs(provider.documentStore());
            assertThat(provider.chunkStore()).isSameAs(provider.chunkStore());
            assertThat(provider.graphStore()).isSameAs(provider.graphStore());
            assertThat(provider.vectorStore()).isSameAs(provider.vectorStore());
            assertThat(provider.snapshotStore()).isSameAs(provider.snapshotStore());
            assertThat(provider.snapshotStore()).isSameAs(snapshotStore);

            var atomicDocumentStore = new AtomicReference<DocumentStore>();
            var atomicChunkStore = new AtomicReference<ChunkStore>();
            var atomicGraphStore = new AtomicReference<GraphStore>();
            var atomicVectorStore = new AtomicReference<VectorStore>();

            provider.writeAtomically(storage -> {
                atomicDocumentStore.set(storage.documentStore());
                atomicChunkStore.set(storage.chunkStore());
                atomicGraphStore.set(storage.graphStore());
                atomicVectorStore.set(storage.vectorStore());
                return null;
            });

            assertThat(atomicDocumentStore.get()).isNotSameAs(provider.documentStore());
            assertThat(atomicChunkStore.get()).isNotSameAs(provider.chunkStore());
            assertThat(atomicGraphStore.get()).isNotSameAs(provider.graphStore());
            assertThat(atomicVectorStore.get()).isNotSameAs(provider.vectorStore());
        }
    }

    @Test
    void topLevelReadsWaitForAtomicWriteToFinish() throws Exception {
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
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));

            var atomicEntered = new CountDownLatch(1);
            var releaseAtomic = new CountDownLatch(1);
            var readAttempted = new CountDownLatch(1);
            var readFinished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            Thread writer = new Thread(() -> {
                try {
                    provider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                        atomicEntered.countDown();
                        await(releaseAtomic);
                        return null;
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread reader = new Thread(() -> {
                try {
                    atomicEntered.await(5, TimeUnit.SECONDS);
                    readAttempted.countDown();
                    provider.documentStore().load("doc-0");
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    readFinished.countDown();
                }
            });

            writer.start();
            reader.start();

            assertThat(atomicEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(readAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(readFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseAtomic.countDown();
            writer.join();
            reader.join();

            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void crossProviderReadsWaitForAtomicWriteToFinish() throws Exception {
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

        try (
            container;
            PostgresStorageProvider writerProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore());
            PostgresStorageProvider readerProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore())
        ) {
            readerProvider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));

            var atomicEntered = new CountDownLatch(1);
            var releaseAtomic = new CountDownLatch(1);
            var readAttempted = new CountDownLatch(1);
            var readFinished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            Thread writer = new Thread(() -> {
                try {
                    writerProvider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                        atomicEntered.countDown();
                        await(releaseAtomic);
                        return null;
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread reader = new Thread(() -> {
                try {
                    atomicEntered.await(5, TimeUnit.SECONDS);
                    readAttempted.countDown();
                    readerProvider.documentStore().load("doc-0");
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    readFinished.countDown();
                }
            });

            writer.start();
            reader.start();

            assertThat(atomicEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(readAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(readFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseAtomic.countDown();
            writer.join();
            reader.join();

            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void topLevelReadsInsideAtomicWriteDoNotDeadlockSameThread() throws Exception {
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
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));

            var finished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            Thread worker = new Thread(() -> {
                try {
                    provider.writeAtomically(storage -> {
                        provider.documentStore().load("doc-0");
                        return null;
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    finished.countDown();
                }
            });

            worker.start();

            assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isTrue();

            worker.join();
            assertThat(failure.get()).isNull();
        }
    }

    @Test
    void crossProviderWritesSerialize() throws Exception {
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

        try (
            container;
            PostgresStorageProvider firstProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore());
            PostgresStorageProvider secondProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore())
        ) {
            var firstEntered = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var secondAttempted = new CountDownLatch(1);
            var secondFinished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            Thread firstWriter = new Thread(() -> {
                try {
                    firstProvider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "First", "body", Map.of()));
                        firstEntered.countDown();
                        await(releaseFirst);
                        return null;
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread secondWriter = new Thread(() -> {
                try {
                    firstEntered.await(5, TimeUnit.SECONDS);
                    secondAttempted.countDown();
                    secondProvider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-2", "Second", "body", Map.of()));
                        return null;
                    });
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    secondFinished.countDown();
                }
            });

            firstWriter.start();
            secondWriter.start();

            assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirst.countDown();
            firstWriter.join();
            secondWriter.join();

            assertThat(failure.get()).isNull();
            assertThat(firstProvider.documentStore().load("doc-1")).isPresent();
            assertThat(secondProvider.documentStore().load("doc-2")).isPresent();
        }
    }

    @Test
    void crossProviderReadsWaitForRestoreToFinish() throws Exception {
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

        try (
            container;
            PostgresStorageProvider restoringProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore());
            PostgresStorageProvider readingProvider = new PostgresStorageProvider(config, new InMemorySnapshotStore());
            var blockingConnection = DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            )
        ) {
            readingProvider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true")));
            blockingConnection.setAutoCommit(false);
            blockingConnection.createStatement().execute(
                "LOCK TABLE " + config.qualifiedTableName("documents") + " IN ACCESS EXCLUSIVE MODE"
            );

            var readAttempted = new CountDownLatch(1);
            var readFinished = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();

            var snapshot = new SnapshotStore.Snapshot(
                List.of(new DocumentStore.DocumentRecord("doc-restore", "Snapshot", "body", Map.of("source", "snapshot"))),
                List.of(),
                List.of(),
                List.of(),
                Map.of()
            );

            Thread restoreThread = new Thread(() -> {
                try {
                    restoringProvider.restore(snapshot);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                }
            });

            Thread reader = new Thread(() -> {
                try {
                    readAttempted.countDown();
                    readingProvider.documentStore().load("doc-0");
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    readFinished.countDown();
                }
            });

            restoreThread.start();

            assertThat(waitForExclusiveAdvisoryLock(config)).isTrue();
            reader.start();
            assertThat(readAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(readFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            blockingConnection.rollback();
            restoreThread.join();
            reader.join();

            assertThat(failure.get()).isNull();
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
    void restoreReplacesDocumentGraphStores() {
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
        var replacementSnapshot = graphSnapshot("doc-1", Instant.parse("2026-04-12T09:00:00Z"));
        var replacementChunkSnapshot = chunkGraphSnapshot("doc-1", "doc-1:0", Instant.parse("2026-04-12T09:00:01Z"));
        var replacementDocumentJournal = documentGraphJournal("doc-1", 2, Instant.parse("2026-04-12T09:00:02Z"));
        var replacementChunkJournal = chunkGraphJournal("doc-1", "doc-1:0", 2, Instant.parse("2026-04-12T09:00:03Z"));

        try (container; PostgresStorageProvider provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
            provider.documentGraphSnapshotStore().saveDocument(graphSnapshot("doc-old", Instant.parse("2026-04-12T08:00:00Z")));
            provider.documentGraphSnapshotStore().saveChunks(
                "doc-old",
                List.of(chunkGraphSnapshot("doc-old", "doc-old:0", Instant.parse("2026-04-12T08:00:01Z")))
            );
            provider.documentGraphJournalStore().appendDocument(
                documentGraphJournal("doc-old", 1, Instant.parse("2026-04-12T08:00:02Z"))
            );
            provider.documentGraphJournalStore().appendChunks(
                "doc-old",
                List.of(chunkGraphJournal("doc-old", "doc-old:0", 1, Instant.parse("2026-04-12T08:00:03Z")))
            );

            provider.restore(new SnapshotStore.Snapshot(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(replacementSnapshot),
                List.of(replacementChunkSnapshot),
                List.of(replacementDocumentJournal),
                List.of(replacementChunkJournal)
            ));

            assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-old")).isEmpty();
            assertThat(provider.documentGraphSnapshotStore().listChunks("doc-old")).isEmpty();
            assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-old")).isEmpty();
            assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-old")).isEmpty();
            assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-1")).contains(replacementSnapshot);
            assertThat(provider.documentGraphSnapshotStore().listChunks("doc-1")).containsExactly(replacementChunkSnapshot);
            assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-1")).containsExactly(replacementDocumentJournal);
            assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-1")).containsExactly(replacementChunkJournal);
        }
    }

    @Test
    void persistsDocumentGraphStateAcrossProviderRestart() {
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
        var snapshot = graphSnapshot("doc-1", Instant.parse("2026-04-12T10:00:00Z"));
        var chunkSnapshot = chunkGraphSnapshot("doc-1", "doc-1:0", Instant.parse("2026-04-12T10:00:01Z"));
        var documentJournal = documentGraphJournal("doc-1", 2, Instant.parse("2026-04-12T10:00:02Z"));
        var chunkJournal = chunkGraphJournal("doc-1", "doc-1:0", 2, Instant.parse("2026-04-12T10:00:03Z"));

        try (container) {
            try (var provider = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
                provider.documentGraphSnapshotStore().saveDocument(snapshot);
                provider.documentGraphSnapshotStore().saveChunks("doc-1", List.of(chunkSnapshot));
                provider.documentGraphJournalStore().appendDocument(documentJournal);
                provider.documentGraphJournalStore().appendChunks("doc-1", List.of(chunkJournal));
            }

            try (var reopened = new PostgresStorageProvider(config, new InMemorySnapshotStore())) {
                assertThat(reopened.documentGraphSnapshotStore().loadDocument("doc-1")).contains(snapshot);
                assertThat(reopened.documentGraphSnapshotStore().listChunks("doc-1")).containsExactly(chunkSnapshot);
                assertThat(reopened.documentGraphJournalStore().listDocumentJournals("doc-1")).containsExactly(documentJournal);
                assertThat(reopened.documentGraphJournalStore().listChunkJournals("doc-1")).containsExactly(chunkJournal);
            }
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

    private static List<String> columnNames(java.sql.Connection connection, PostgresStorageConfig config, String baseTableName)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = ?
                ORDER BY ordinal_position
                """
        )) {
            statement.setString(1, config.schema());
            statement.setString(2, config.tableName(baseTableName));
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
                return columns;
            }
        }
    }

    private static List<String> primaryKeyColumns(java.sql.Connection connection, PostgresStorageConfig config, String baseTableName)
        throws SQLException {
        try (var statement = connection.prepareStatement(
            """
                SELECT attribute.attname AS column_name
                FROM pg_index index_def
                JOIN pg_class klass ON klass.oid = index_def.indrelid
                JOIN pg_namespace namespace ON namespace.oid = klass.relnamespace
                JOIN pg_attribute attribute
                  ON attribute.attrelid = klass.oid
                 AND attribute.attnum = ANY(index_def.indkey)
                WHERE namespace.nspname = ?
                  AND klass.relname = ?
                  AND index_def.indisprimary
                ORDER BY array_position(index_def.indkey, attribute.attnum)
                """
        )) {
            statement.setString(1, config.schema());
            statement.setString(2, config.tableName(baseTableName));
            try (var resultSet = statement.executeQuery()) {
                List<String> columns = new ArrayList<>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString("column_name"));
                }
                return columns;
            }
        }
    }

    private static java.util.Optional<Integer> schemaVersion(java.sql.Connection connection, PostgresStorageConfig config) throws SQLException {
        try (var statement = connection.prepareStatement(
            """
                SELECT version
                FROM %s
                WHERE schema_key = 'storage'
                """.formatted(config.qualifiedTableName("schema_version"))
        )) {
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(resultSet.getInt("version"));
            }
        }
    }

    private static void createLegacySchema(java.sql.Connection connection, PostgresStorageConfig config) throws SQLException {
        connection.createStatement().execute("CREATE EXTENSION IF NOT EXISTS vector");
        connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.formatted(config.qualifiedTableName("documents"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                document_id TEXT NOT NULL,
                text TEXT NOT NULL,
                token_count INTEGER NOT NULL,
                chunk_order INTEGER NOT NULL,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.formatted(config.qualifiedTableName("chunks"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                description TEXT NOT NULL
            )
            """.formatted(config.qualifiedTableName("entities"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                entity_id TEXT NOT NULL,
                alias TEXT NOT NULL,
                PRIMARY KEY (entity_id, alias)
            )
            """.formatted(config.qualifiedTableName("entity_aliases"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                entity_id TEXT NOT NULL,
                chunk_id TEXT NOT NULL,
                PRIMARY KEY (entity_id, chunk_id)
            )
            """.formatted(config.qualifiedTableName("entity_chunks"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                id TEXT PRIMARY KEY,
                source_entity_id TEXT NOT NULL,
                target_entity_id TEXT NOT NULL,
                type TEXT NOT NULL,
                description TEXT NOT NULL,
                weight DOUBLE PRECISION NOT NULL
            )
            """.formatted(config.qualifiedTableName("relations"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                relation_id TEXT NOT NULL,
                chunk_id TEXT NOT NULL,
                PRIMARY KEY (relation_id, chunk_id)
            )
            """.formatted(config.qualifiedTableName("relation_chunks"))
        );
        connection.createStatement().execute(
            """
            CREATE TABLE IF NOT EXISTS %s (
                namespace TEXT NOT NULL,
                vector_id TEXT NOT NULL,
                embedding vector(%d) NOT NULL,
                PRIMARY KEY (namespace, vector_id)
            )
            """.formatted(config.qualifiedTableName("vectors"), config.vectorDimensions())
        );
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

    private static boolean waitForExclusiveAdvisoryLock(PostgresStorageConfig config) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (exclusiveAdvisoryLockIsHeld(config)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean exclusiveAdvisoryLockIsHeld(PostgresStorageConfig config) throws SQLException {
        try (var connection = DriverManager.getConnection(
            config.jdbcUrl(),
            config.username(),
            config.password()
        )) {
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock_shared(?)")) {
                statement.setLong(1, deriveLockKey(config));
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    boolean acquired = resultSet.getBoolean(1);
                    if (acquired) {
                        try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock_shared(?)")) {
                            unlock.setLong(1, deriveLockKey(config));
                            unlock.executeQuery();
                        }
                        return false;
                    }
                    return true;
                }
            }
        }
    }

    private static long deriveLockKey(PostgresStorageConfig config) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((config.schema() + ":" + config.tablePrefix() + ":default").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(Arrays.copyOf(digest, Long.BYTES)).getLong();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting on latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting on latch", exception);
        }
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
    }

    private static DocumentGraphSnapshotStore.DocumentGraphSnapshot graphSnapshot(String documentId, Instant now) {
        return new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            1,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            1,
            now.minusSeconds(1),
            now,
            null
        );
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkGraphSnapshot(
        String documentId,
        String chunkId,
        Instant now
    ) {
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            documentId,
            chunkId,
            0,
            "hash-" + chunkId,
            ChunkExtractStatus.SUCCEEDED,
            List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                "entity-a",
                "person",
                "desc",
                List.of("alias-a")
            )),
            List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                "entity-a",
                "entity-b",
                "works_with",
                "rel-desc",
                1.0d
            )),
            now,
            null
        );
    }

    private static DocumentGraphJournalStore.DocumentGraphJournal documentGraphJournal(String documentId, int version, Instant now) {
        return new DocumentGraphJournalStore.DocumentGraphJournal(
            documentId,
            version,
            GraphMaterializationStatus.MERGED,
            GraphMaterializationMode.AUTO,
            1,
            1,
            1,
            1,
            FailureStage.FINALIZING,
            now.minusSeconds(1),
            now,
            null
        );
    }

    private static DocumentGraphJournalStore.ChunkGraphJournal chunkGraphJournal(
        String documentId,
        String chunkId,
        int version,
        Instant now
    ) {
        return new DocumentGraphJournalStore.ChunkGraphJournal(
            documentId,
            chunkId,
            version,
            ChunkMergeStatus.SUCCEEDED,
            ChunkGraphStatus.MATERIALIZED,
            List.of("entity-a"),
            List.of("relation-a"),
            List.of("entity-a"),
            List.of("relation-a"),
            FailureStage.ENTITY_MATERIALIZATION,
            now,
            null
        );
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
