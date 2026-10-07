package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresVectorStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newPostgresContainer();

    @Test
    void storesVectorsByNamespaceAndId() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
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
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            var second = new VectorStore.VectorRecord("vec-2", List.of(1.0d, 0.0d, 0.0d));
            var first = new VectorStore.VectorRecord("vec-1", List.of(0.0d, 1.0d, 0.0d));

            resources.store().saveAll("chunks", List.of(second, first));

            assertThat(resources.store().list("chunks")).containsExactly(first, second);
        }
    }

    @Test
    void returnsTopKSimilarityByNamespace() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
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
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            assertThatThrownBy(() -> resources.store().saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d)))
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector dimensions");
        }
    }

    @Test
    void returnsEmptyForNonPositiveTopKBeforeValidatingDimensions() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            assertThat(resources.store().search("chunks", List.of(1.0d, 0.0d), 0)).isEmpty();
        }
    }

    @Test
    void returnsEmptyForMissingNamespaceBeforeValidatingDimensions() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            assertThat(resources.store().search("missing", List.of(1.0d, 0.0d), 1)).isEmpty();
        }
    }

    @Test
    void deletesVectorsByIdWithinTheirNamespaceOnly() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            resources.store().saveAll(
                "chunks",
                List.of(
                    new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)),
                    new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
                )
            );
            resources.store().saveAll(
                "entities",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(0.0d, 0.0d, 1.0d)))
            );

            resources.store().deleteIds("chunks", List.of("chunk-1", "missing"));

            assertThat(resources.store().list("chunks")).containsExactly(
                new VectorStore.VectorRecord("chunk-2", List.of(0.0d, 1.0d, 0.0d))
            );
            assertThat(resources.store().list("entities")).containsExactly(
                new VectorStore.VectorRecord("chunk-1", List.of(0.0d, 0.0d, 1.0d))
            );
        }
    }

    @Test
    void ignoresDeletesWithoutIds() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            resources.store().saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)))
            );

            resources.store().deleteIds("chunks", List.of());

            assertThat(resources.store().list("chunks")).containsExactly(
                new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d))
            );
        }
    }

    @Test
    void deletesWholeNamespace() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            resources.store().saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 0.0d, 0.0d)))
            );
            resources.store().saveAll(
                "entities",
                List.of(new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d)))
            );

            resources.store().deleteNamespace("chunks");

            assertThat(resources.store().list("chunks")).isEmpty();
            assertThat(resources.store().list("entities")).containsExactly(
                new VectorStore.VectorRecord("entity-1", List.of(0.0d, 1.0d, 0.0d))
            );
        }
    }

    @Test
    void createsHnswIndexOnTheEmbeddingColumn() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            try (var connection = java.sql.DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
            ); var statement = connection.prepareStatement(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = ?
                  AND tablename = ?
                  AND indexname = ?
                """
            )) {
                statement.setString(1, config.schema());
                statement.setString(2, config.tableName("vectors"));
                statement.setString(3, config.tableName("vectors") + "_embedding_hnsw_idx");
                try (var resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    var indexDefinition = resultSet.getString("indexdef");
                    assertThat(indexDefinition).contains("hnsw");
                    assertThat(indexDefinition).contains("vector_ip_ops");
                }
            } catch (java.sql.SQLException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static StoreResources newStoreResources(PostgresStorageConfig config) {
        var dataSource = newDataSource(config);
        new PostgresSchemaManager(dataSource, config).bootstrap();
        return new StoreResources(dataSource, new PostgresVectorStore(dataSource, config));
    }

    private static PostgresStorageConfig newConfig() {
        return new PostgresStorageConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "lightrag_" + UUID.randomUUID().toString().replace("-", ""),
            3,
            "rag_"
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

    private record StoreResources(HikariDataSource dataSource, PostgresVectorStore store) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
