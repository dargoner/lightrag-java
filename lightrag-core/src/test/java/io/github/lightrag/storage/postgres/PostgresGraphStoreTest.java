package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.storage.GraphStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PostgresGraphStoreTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newPostgresContainer();

    @Test
    void savesAndLoadsEntitiesAndRelations() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            var entity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A", "Alice A."),
                List.of("chunk-1", "chunk-2")
            );
            var relation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1", "chunk-2")
            );

            resources.store().saveEntity(entity);
            resources.store().saveRelation(relation);

            assertThat(resources.store().loadEntity("entity-1")).contains(entity);
            assertThat(resources.store().loadRelation("relation-1")).contains(relation);
        }
    }

    @Test
    void listsEntitiesAndRelationsInDeterministicOrder() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            var secondEntity = new GraphStore.EntityRecord("entity-2", "Bob", "person", "Engineer", List.of(), List.of("chunk-2"));
            var firstEntity = new GraphStore.EntityRecord("entity-1", "Alice", "person", "Researcher", List.of("A"), List.of("chunk-1"));
            var secondRelation = new GraphStore.RelationRecord(
                "relation-2",
                "entity-2",
                "entity-3",
                "manages",
                "Bob manages Carol",
                0.8d,
                List.of("chunk-2")
            );
            var firstRelation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );

            resources.store().saveEntity(secondEntity);
            resources.store().saveEntity(firstEntity);
            resources.store().saveRelation(secondRelation);
            resources.store().saveRelation(firstRelation);

            assertThat(resources.store().allEntities()).containsExactly(firstEntity, secondEntity);
            assertThat(resources.store().allRelations()).containsExactly(firstRelation, secondRelation);
        }
    }

    @Test
    void findsOneHopRelationsForEntity() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            var first = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );
            var second = new GraphStore.RelationRecord(
                "relation-2",
                "entity-2",
                "entity-3",
                "manages",
                "Bob manages Carol",
                0.8d,
                List.of("chunk-2")
            );

            resources.store().saveRelation(second);
            resources.store().saveRelation(first);

            assertThat(resources.store().findRelations("entity-2")).containsExactly(first, second);
        }
    }

    @Test
    void overwritingRelationUpdatesEndpointIndexing() {
        var config = newConfig();
        try (var resources = newStoreResources(config)) {
            var original = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );
            var replacement = new GraphStore.RelationRecord(
                "relation-1",
                "entity-3",
                "entity-4",
                "reports_to",
                "Carol reports to Dave",
                0.7d,
                List.of("chunk-2")
            );

            resources.store().saveRelation(original);
            resources.store().saveRelation(replacement);

            assertThat(resources.store().loadRelation("relation-1")).contains(replacement);
            assertThat(resources.store().findRelations("entity-1")).isEmpty();
            assertThat(resources.store().findRelations("entity-2")).isEmpty();
            assertThat(resources.store().findRelations("entity-3")).containsExactly(replacement);
            assertThat(resources.store().findRelations("entity-4")).containsExactly(replacement);
        }
    }

    @Test
    void replayingBootstrapDoesNotDeleteExistingRelations() {
        var config = newConfig();
        var dataSource = newDataSource(config);
        try (dataSource) {
            var manager = new PostgresSchemaManager(dataSource, config);
            var relation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                "chunk-1<SEP>chunk-2",
                "/tmp/doc.md"
            );

            manager.bootstrap();
            new PostgresGraphStore(dataSource, config).saveRelation(relation);

            manager.bootstrap();

            assertThat(new PostgresGraphStore(dataSource, config).loadRelation("relation-1")).contains(relation);
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
        return new StoreResources(dataSource, new PostgresGraphStore(dataSource, config));
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

    private record StoreResources(HikariDataSource dataSource, PostgresGraphStore store) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
