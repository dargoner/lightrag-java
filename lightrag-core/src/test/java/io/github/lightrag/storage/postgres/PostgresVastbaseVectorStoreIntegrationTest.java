package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in round trip against a real Vastbase G100 instance. Enabled only when
 * {@code LIGHTRAG_VASTBASE_IT=true} and the three connection variables are present, so the default
 * build never depends on an external database.
 */
class PostgresVastbaseVectorStoreIntegrationTest {
    @Test
    void roundTripsVectorsThroughFloatvectorWithoutVectorExtension() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("LIGHTRAG_VASTBASE_IT")));
        var jdbcUrl = requireEnv("LIGHTRAG_VASTBASE_JDBC_URL");
        var username = requireEnv("LIGHTRAG_VASTBASE_USERNAME");
        var password = requireEnv("LIGHTRAG_VASTBASE_PASSWORD");
        var schema = "lightrag_vastbase_it_" + UUID.randomUUID().toString().replace("-", "");
        var config = new PostgresStorageConfig(
            jdbcUrl,
            username,
            password,
            schema,
            3,
            "rag_",
            PostgresVectorDialect.AUTO
        );

        createSchema(jdbcUrl, username, password, schema);
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setSchema(schema);
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);

        try (var dataSource = new HikariDataSource(hikariConfig);
             var provider = new PostgresStorageProvider(
                 dataSource,
                 config,
                 new NoopSnapshotStore(),
                 "vastbase-it"
             )) {
            var store = provider.vectorStore();
            store.saveAll(
                "chunks",
                List.of(new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 2.0d, 3.0d)))
            );

            assertThat(store.list("chunks")).containsExactly(
                new VectorStore.VectorRecord("chunk-1", List.of(1.0d, 2.0d, 3.0d))
            );
            assertThat(store.search("chunks", List.of(1.0d, 2.0d, 3.0d), 1))
                .hasSize(1)
                .first()
                .satisfies(match -> {
                    assertThat(match.id()).isEqualTo("chunk-1");
                    assertThat(match.score()).isCloseTo(14.0d, within(1.0e-6));
                });
        } finally {
            dropSchema(jdbcUrl, username, password, schema);
        }
    }

    private static String requireEnv(String key) {
        var value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required when LIGHTRAG_VASTBASE_IT=true");
        }
        return value;
    }

    private static void createSchema(String jdbcUrl, String username, String password, String schema) {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Vastbase integration schema", exception);
        }
    }

    private static void dropSchema(String jdbcUrl, String username, String password, String schema) {
        try (var connection = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to drop Vastbase integration schema", exception);
        }
    }

    private static final class NoopSnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("Not needed for the vector round trip");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
