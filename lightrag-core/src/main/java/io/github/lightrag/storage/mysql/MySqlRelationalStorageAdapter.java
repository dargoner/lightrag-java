package io.github.lightrag.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.RelationalStorageAdapter;
import io.github.lightrag.storage.SnapshotStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MySqlRelationalStorageAdapter implements RelationalStorageAdapter {
    private static final int DEFAULT_POOL_SIZE = 4;

    private final DataSource dataSource;
    private final HikariDataSource ownedDataSource;
    private final boolean ownsDataSource;
    private final MySqlStorageConfig config;
    private final SnapshotStore snapshotStore;
    private final String workspaceId;
    private final DocumentStore documentStore;
    private final ChunkStore chunkStore;
    private final DocumentStatusStore documentStatusStore;

    public MySqlRelationalStorageAdapter(
        DataSource dataSource,
        MySqlStorageConfig config,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            Objects.requireNonNull(dataSource, "dataSource"),
            false,
            Objects.requireNonNull(config, "config"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
        );
    }

    public MySqlRelationalStorageAdapter(
        MySqlStorageConfig config,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            createDataSource(Objects.requireNonNull(config, "config"), "lightrag-mysql"),
            true,
            config,
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
        );
    }

    private MySqlRelationalStorageAdapter(
        DataSource dataSource,
        boolean ownsDataSource,
        MySqlStorageConfig config,
        SnapshotStore snapshotStore,
        String workspaceId
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ownedDataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
        this.ownsDataSource = ownsDataSource;
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.workspaceId = new WorkspaceScope(Objects.requireNonNull(workspaceId, "workspaceId")).workspaceId();
        new MySqlSchemaManager(this.dataSource, this.config).bootstrap();
        this.documentStore = new MySqlDocumentStore(this.dataSource, this.config, this.workspaceId);
        this.chunkStore = new MySqlChunkStore(this.dataSource, this.config, this.workspaceId);
        this.documentStatusStore = new MySqlDocumentStatusStore(this.dataSource, this.config, this.workspaceId);
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
    public DocumentStatusStore documentStatusStore() {
        return documentStatusStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public SnapshotStore.Snapshot captureSnapshot() {
        return new SnapshotStore.Snapshot(
            documentStore.list(),
            chunkStore.list(),
            List.of(),
            List.of(),
            Map.of(),
            documentStatusStore.list()
        );
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = toRelationalRestoreSnapshot(Objects.requireNonNull(snapshot, "snapshot"));
        try (var connection = dataSource.getConnection()) {
            withTransaction(connection, () -> {
                deleteWorkspaceRows(connection, "document_status");
                deleteWorkspaceRows(connection, "chunks");
                deleteWorkspaceRows(connection, "documents");

                var connectionAccess = MySqlJdbcConnectionAccess.forConnection(connection);
                var transactionalDocumentStore = new MySqlDocumentStore(connectionAccess, config, workspaceId);
                var transactionalChunkStore = new MySqlChunkStore(connectionAccess, config, workspaceId);
                var transactionalStatusStore = new MySqlDocumentStatusStore(connectionAccess, config, workspaceId);
                for (var document : source.documents()) {
                    transactionalDocumentStore.save(document);
                }
                for (var chunk : source.chunks()) {
                    transactionalChunkStore.save(chunk);
                }
                for (var status : source.documentStatuses()) {
                    transactionalStatusStore.save(status);
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new StorageException("Failed to open MySQL transaction for restore", exception);
        }
    }

    @Override
    public <T> T writeInTransaction(RelationalWriteOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (var connection = dataSource.getConnection()) {
            var connectionAccess = MySqlJdbcConnectionAccess.forConnection(connection);
            return withTransaction(connection, () -> operation.execute(new RelationalStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return new MySqlDocumentStore(connectionAccess, config, workspaceId);
                }

                @Override
                public ChunkStore chunkStore() {
                    return new MySqlChunkStore(connectionAccess, config, workspaceId);
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return new MySqlDocumentStatusStore(connectionAccess, config, workspaceId);
                }
            }));
        } catch (SQLException exception) {
            throw new StorageException("Failed to open MySQL transaction", exception);
        }
    }

    @Override
    public void close() {
        if (ownsDataSource && ownedDataSource != null) {
            ownedDataSource.close();
        }
    }

    DataSource dataSource() {
        return dataSource;
    }

    MySqlStorageConfig config() {
        return config;
    }

    String workspaceId() {
        return workspaceId;
    }

    private static HikariDataSource createDataSource(MySqlStorageConfig config, String poolName) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setPoolName(poolName);
        return new HikariDataSource(hikariConfig);
    }

    private void deleteWorkspaceRows(Connection connection, String baseTableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + config.qualifiedTableName(baseTableName) + " WHERE workspace_id = ?"
        )) {
            statement.setString(1, workspaceId);
            statement.executeUpdate();
        }
    }

    private <T> T withTransaction(Connection connection, SqlSupplier<T> supplier) {
        Throwable primaryFailure = null;
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = supplier.get();
                connection.commit();
                return result;
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                rollback(connection, failure);
                throw failure;
            } catch (SQLException exception) {
                primaryFailure = exception;
                rollback(connection, exception);
                throw new StorageException("MySQL transaction failed", exception);
            } finally {
                try {
                    restoreAutoCommit(connection, originalAutoCommit);
                } catch (SQLException exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(exception);
                    } else {
                        throw new StorageException("Failed to restore MySQL connection state", exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to configure MySQL transaction", exception);
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

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
