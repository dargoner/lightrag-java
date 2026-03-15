package io.github.lightragjava.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.exception.StorageException;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Objects;

public final class PostgresStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private final HikariDataSource dataSource;
    private final SnapshotStore snapshotStore;
    private final PostgresDocumentStore documentStore;
    private final PostgresChunkStore chunkStore;
    private final PostgresGraphStore graphStore;
    private final PostgresVectorStore vectorStore;
    private final PostgresStorageConfig config;

    public PostgresStorageProvider(PostgresStorageConfig config, SnapshotStore snapshotStore) {
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.dataSource = createDataSource(config);
        try {
            new PostgresSchemaManager(dataSource, config).bootstrap();
            this.documentStore = new PostgresDocumentStore(dataSource, config);
            this.chunkStore = new PostgresChunkStore(dataSource, config);
            this.graphStore = new PostgresGraphStore(dataSource, config);
            this.vectorStore = new PostgresVectorStore(dataSource, config);
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    @Override
    public DocumentStore documentStore() {
        return documentStore;
    }

    @Override
    public ChunkStore chunkStore() {
        return chunkStore;
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return vectorStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (var connection = dataSource.getConnection()) {
            return withTransaction(connection, () -> operation.execute(newAtomicView(connection)));
        } catch (SQLException exception) {
            throw new StorageException("Failed to open PostgreSQL transaction", exception);
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        try (var connection = dataSource.getConnection()) {
            withTransaction(connection, () -> {
                truncateAll(connection);
                var stores = newAtomicView(connection);

                for (var document : source.documents()) {
                    stores.documentStore().save(document);
                }
                for (var chunk : source.chunks()) {
                    stores.chunkStore().save(chunk);
                }
                for (var entity : source.entities()) {
                    stores.graphStore().saveEntity(entity);
                }
                for (var relation : source.relations()) {
                    stores.graphStore().saveRelation(relation);
                }
                for (var namespaceEntry : source.vectors().entrySet()) {
                    stores.vectorStore().saveAll(namespaceEntry.getKey(), namespaceEntry.getValue());
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new StorageException("Failed to open PostgreSQL transaction for restore", exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private AtomicStorageView newAtomicView(Connection connection) {
        var connectionAccess = JdbcConnectionAccess.forConnection(connection);
        return new AtomicView(
            new PostgresDocumentStore(connectionAccess, config),
            new PostgresChunkStore(connectionAccess, config),
            new PostgresGraphStore(connectionAccess, config),
            new PostgresVectorStore(connectionAccess, config)
        );
    }

    private <T> T withTransaction(Connection connection, SqlSupplier<T> supplier) {
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = supplier.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                rollback(connection, failure);
                throw failure;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new StorageException("PostgreSQL transaction failed", exception);
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to configure PostgreSQL transaction", exception);
        }
    }

    private void truncateAll(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("documents"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("chunks"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("entities"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("entity_aliases"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("entity_chunks"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("relations"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("relation_chunks"));
            statement.execute("TRUNCATE TABLE " + config.qualifiedTableName("vectors"));
        }
    }

    private static void rollback(Connection connection, Throwable original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean originalAutoCommit) throws SQLException {
        if (connection.getAutoCommit() != originalAutoCommit) {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static HikariDataSource createDataSource(PostgresStorageConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(4);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setPoolName("lightrag-postgres");
        return new HikariDataSource(hikariConfig);
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore
    ) implements AtomicStorageView {
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
