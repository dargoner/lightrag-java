package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.storage.DocumentStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresDocumentStoreTest {
    @Test
    void savesAndLoadsDocumentsById() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            var document = new DocumentStore.DocumentRecord(
                "doc-1",
                "Title",
                "Body",
                Map.of("source", "unit-test")
            );

            resources.store().save(document);

            assertThat(resources.store().load("doc-1")).contains(document);
        }
    }

    @Test
    void listsDocumentsInDeterministicIdOrder() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            var second = new DocumentStore.DocumentRecord("doc-2", "Second", "Body 2", Map.of());
            var first = new DocumentStore.DocumentRecord("doc-1", "First", "Body 1", Map.of("source", "test"));

            resources.store().save(second);
            resources.store().save(first);

            assertThat(resources.store().list()).containsExactly(first, second);
        }
    }

    @Test
    void reportsContainsForStoredDocumentIds() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.store().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));

            assertThat(resources.store().contains("doc-1")).isTrue();
            assertThat(resources.store().contains("missing")).isFalse();
        }
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static StoreResources newStoreResources(PostgreSQLContainer<?> container) {
        container.start();
        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        var dataSource = newDataSource(config);
        new PostgresSchemaManager(dataSource, config).bootstrap();
        return new StoreResources(dataSource, new PostgresDocumentStore(dataSource, config));
    }

    private static HikariDataSource newDataSource(PostgresStorageConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private record StoreResources(HikariDataSource dataSource, PostgresDocumentStore store) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
