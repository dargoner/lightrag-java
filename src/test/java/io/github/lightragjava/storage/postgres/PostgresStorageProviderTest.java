package io.github.lightragjava.storage.postgres;

import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.storage.SnapshotStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
    void writeAtomicallyRemainsUnavailableUntilTransactionalStoresExist() {
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
            assertThatThrownBy(() -> provider.writeAtomically(storage -> null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Atomic writes");
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
