package io.github.lightragjava.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresChunkStoreTest {
    @Test
    void savesAndLoadsChunksById() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
            var chunk = new ChunkStore.ChunkRecord(
                "chunk-1",
                "doc-1",
                "First chunk",
                10,
                0,
                Map.of("kind", "intro")
            );

            resources.store().save(chunk);

            assertThat(resources.store().load("chunk-1")).contains(chunk);
        }
    }

    @Test
    void listsChunksInDeterministicIdOrder() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of()));
            var second = new ChunkStore.ChunkRecord("chunk-2", "doc-1", "Second", 10, 1, Map.of("kind", "body"));
            var first = new ChunkStore.ChunkRecord("chunk-1", "doc-1", "First", 8, 0, Map.of("kind", "intro"));

            resources.store().save(second);
            resources.store().save(first);

            assertThat(resources.store().list()).containsExactly(first, second);
        }
    }

    @Test
    void listsDocumentChunksByOrderThenId() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Doc 1", "Body 1", Map.of()));
            resources.documentStore().save(new DocumentStore.DocumentRecord("doc-2", "Doc 2", "Body 2", Map.of()));
            var laterSameOrder = new ChunkStore.ChunkRecord("chunk-2", "doc-1", "Later", 9, 0, Map.of());
            var first = new ChunkStore.ChunkRecord("chunk-1", "doc-1", "First", 8, 0, Map.of());
            var second = new ChunkStore.ChunkRecord("chunk-3", "doc-1", "Second", 7, 1, Map.of());
            var otherDocument = new ChunkStore.ChunkRecord("chunk-4", "doc-2", "Other", 6, 0, Map.of());

            resources.store().save(second);
            resources.store().save(otherDocument);
            resources.store().save(laterSameOrder);
            resources.store().save(first);

            assertThat(resources.store().listByDocument("doc-1")).containsExactly(first, laterSameOrder, second);
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
        return new StoreResources(
            dataSource,
            new PostgresDocumentStore(dataSource, config),
            new PostgresChunkStore(dataSource, config)
        );
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

    private record StoreResources(
        HikariDataSource dataSource,
        PostgresDocumentStore documentStore,
        PostgresChunkStore store
    ) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
