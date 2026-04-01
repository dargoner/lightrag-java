package io.github.lightrag.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlStoresTest {
    @Test
    void documentChunkAndStatusStoresRoundTripRecords() {
        try (
            var container = newMySqlContainer();
            var resources = newStoreResources(container, "default");
        ) {
            var document = new DocumentStore.DocumentRecord(
                "doc-1",
                "Title",
                "Body",
                Map.of("source", "unit-test")
            );
            var chunk = new ChunkStore.ChunkRecord(
                "chunk-1",
                "doc-1",
                "First chunk",
                12,
                0,
                Map.of("kind", "intro")
            );
            var status = new DocumentStatusStore.StatusRecord(
                "doc-1",
                DocumentStatus.PROCESSED,
                "processed",
                null
            );

            resources.documentStore().save(document);
            resources.chunkStore().save(chunk);
            resources.statusStore().save(status);

            assertThat(resources.documentStore().load("doc-1")).contains(document);
            assertThat(resources.documentStore().contains("doc-1")).isTrue();
            assertThat(resources.documentStore().list()).containsExactly(document);
            assertThat(resources.chunkStore().load("chunk-1")).contains(chunk);
            assertThat(resources.chunkStore().list()).containsExactly(chunk);
            assertThat(resources.chunkStore().listByDocument("doc-1")).containsExactly(chunk);
            assertThat(resources.statusStore().load("doc-1")).contains(status);
            assertThat(resources.statusStore().list()).containsExactly(status);
        }
    }

    @Test
    void storesIsolateRowsByWorkspaceId() {
        try (
            var container = newMySqlContainer();
            var alpha = newStoreResources(container, "alpha");
            var beta = newStoreResources(container, "beta");
        ) {
            var alphaDocument = new DocumentStore.DocumentRecord("doc-1", "Alpha", "alpha", Map.of("workspace", "alpha"));
            var betaDocument = new DocumentStore.DocumentRecord("doc-1", "Beta", "beta", Map.of("workspace", "beta"));
            var alphaChunk = new ChunkStore.ChunkRecord("chunk-1", "doc-1", "alpha", 4, 0, Map.of("workspace", "alpha"));
            var betaChunk = new ChunkStore.ChunkRecord("chunk-1", "doc-1", "beta", 5, 0, Map.of("workspace", "beta"));
            var alphaStatus = new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "alpha", null);
            var betaStatus = new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.FAILED, "beta", "boom");

            alpha.documentStore().save(alphaDocument);
            alpha.chunkStore().save(alphaChunk);
            alpha.statusStore().save(alphaStatus);
            beta.documentStore().save(betaDocument);
            beta.chunkStore().save(betaChunk);
            beta.statusStore().save(betaStatus);

            assertThat(alpha.documentStore().load("doc-1")).contains(alphaDocument);
            assertThat(beta.documentStore().load("doc-1")).contains(betaDocument);
            assertThat(alpha.chunkStore().listByDocument("doc-1")).containsExactly(alphaChunk);
            assertThat(beta.chunkStore().listByDocument("doc-1")).containsExactly(betaChunk);
            assertThat(alpha.statusStore().load("doc-1")).contains(alphaStatus);
            assertThat(beta.statusStore().load("doc-1")).contains(betaStatus);
        }
    }

    @Test
    void bootstrapRejectsLegacySchemaWithoutWorkspaceColumns() throws Exception {
        try (var container = newMySqlContainer()) {
            container.start();
            var config = new MySqlStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "rag_"
            );

            try (
                var connection = DriverManager.getConnection(
                    config.jdbcUrl(),
                    config.username(),
                    config.password()
                );
                var statement = connection.createStatement()
            ) {
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        id VARCHAR(255) PRIMARY KEY,
                        title TEXT NOT NULL,
                        content LONGTEXT NOT NULL,
                        metadata JSON NOT NULL
                    )
                    """.formatted(config.qualifiedTableName("documents"))
                );
            }

            try (var dataSource = newDataSource(config)) {
                assertThatThrownBy(() -> new MySqlSchemaManager(dataSource, config).bootstrap())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("legacy")
                    .hasMessageContaining("workspace");
            }
        }
    }

    private static MySQLContainer<?> newMySqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    private static StoreResources newStoreResources(MySQLContainer<?> container, String workspaceId) {
        container.start();
        var config = new MySqlStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "rag_"
        );
        var dataSource = newDataSource(config);
        new MySqlSchemaManager(dataSource, config).bootstrap();
        return new StoreResources(
            dataSource,
            new MySqlDocumentStore(dataSource, config, workspaceId),
            new MySqlChunkStore(dataSource, config, workspaceId),
            new MySqlDocumentStatusStore(dataSource, config, workspaceId)
        );
    }

    private static HikariDataSource newDataSource(MySqlStorageConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private record StoreResources(
        HikariDataSource dataSource,
        MySqlDocumentStore documentStore,
        MySqlChunkStore chunkStore,
        MySqlDocumentStatusStore statusStore
    ) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
