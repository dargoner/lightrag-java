package io.github.lightragjava.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresVectorStoreTest {
    @Test
    void storesVectorsByNamespaceAndId() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.store().saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)))
            );
            resources.store().saveAll(
                "entities",
                List.of(new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d)))
            );

            assertThat(resources.store().list("chunks")).containsExactly(
                new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d))
            );
            assertThat(resources.store().list("entities")).containsExactly(
                new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d))
            );
        }
    }

    @Test
    void listsVectorsInDeterministicIdOrder() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            var second = new VectorStore.VectorRecord("vec-2", List.of(1.0d, 0.0d, 0.0d));
            var first = new VectorStore.VectorRecord("vec-1", List.of(0.0d, 1.0d, 0.0d));

            resources.store().saveAll("chunks", List.of(second, first));

            assertThat(resources.store().list("chunks")).containsExactly(first, second);
        }
    }

    @Test
    void returnsTopKSimilarityByNamespace() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            resources.store().saveAll(
                "chunks",
                List.of(
                    new VectorStore.VectorRecord("chunk-2", List.of(1.0d, 0.0d, 0.0d)),
                    new VectorStore.VectorRecord("chunk-3", List.of(0.0d, 1.0d, 0.0d)),
                    new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d))
                )
            );
            resources.store().saveAll(
                "entities",
                List.of(new VectorStore.VectorRecord("entity-1", List.of(1.0d, 0.0d, 0.0d)))
            );

            assertThat(resources.store().search("chunks", List.of(1.0d, 0.0d, 0.0d), 2)).containsExactly(
                new VectorStore.VectorMatch("chunk-1", 1.0d),
                new VectorStore.VectorMatch("chunk-2", 1.0d)
            );
        }
    }

    @Test
    void rejectsMismatchedVectorDimensions() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container);
        ) {
            assertThatThrownBy(() -> resources.store().saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d)))
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector dimensions");
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
        return new StoreResources(dataSource, new PostgresVectorStore(dataSource, config));
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

    private record StoreResources(HikariDataSource dataSource, PostgresVectorStore store) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
