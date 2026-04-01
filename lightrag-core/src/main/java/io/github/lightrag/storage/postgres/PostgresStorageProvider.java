package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PostgresStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PostgresStorageProvider.class);
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");

    private final ReentrantReadWriteLock lock;
    private final HikariDataSource dataSource;
    private final HikariDataSource lockDataSource;
    private final DataSource jdbcDataSource;
    private final DataSource jdbcLockDataSource;
    private final PostgresAdvisoryLockManager advisoryLockManager;
    private final ThreadLocal<Boolean> exclusiveAdvisoryLockHeld;
    private final SnapshotStore snapshotStore;
    private final DocumentStatusStore documentStatusStore;
    private final PostgresDocumentStore documentStore;
    private final PostgresChunkStore chunkStore;
    private final PostgresGraphStore graphStore;
    private final PostgresVectorStore vectorStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final GraphStore lockedGraphStore;
    private final VectorStore lockedVectorStore;
    private final PostgresStorageConfig config;
    private final String workspaceId;
    private final boolean ownsDataSource;
    private final boolean ownsLockDataSource;

    public PostgresStorageProvider(PostgresStorageConfig config, SnapshotStore snapshotStore) {
        this(config, snapshotStore, DEFAULT_WORKSPACE.workspaceId());
    }

    public PostgresStorageProvider(PostgresStorageConfig config, SnapshotStore snapshotStore, String workspaceId) {
        this(
            createDataSource(Objects.requireNonNull(config, "config"), "lightrag-postgres"),
            createDataSource(config, "lightrag-postgres-locks"),
            true,
            true,
            config,
            snapshotStore,
            workspaceId
        );
    }

    public PostgresStorageProvider(DataSource dataSource, PostgresStorageConfig config, SnapshotStore snapshotStore) {
        this(dataSource, config, snapshotStore, DEFAULT_WORKSPACE.workspaceId());
    }

    public PostgresStorageProvider(DataSource dataSource, PostgresStorageConfig config, SnapshotStore snapshotStore, String workspaceId) {
        this(
            Objects.requireNonNull(dataSource, "dataSource"),
            dataSource,
            false,
            false,
            config,
            snapshotStore,
            workspaceId
        );
    }

    private PostgresStorageProvider(
        DataSource dataSource,
        DataSource lockDataSource,
        boolean ownsDataSource,
        boolean ownsLockDataSource,
        PostgresStorageConfig config,
        SnapshotStore snapshotStore,
        String workspaceId
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.workspaceId = new WorkspaceScope(workspaceId).workspaceId();
        this.jdbcDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcLockDataSource = Objects.requireNonNull(lockDataSource, "lockDataSource");
        this.ownsDataSource = ownsDataSource;
        this.ownsLockDataSource = ownsLockDataSource;
        this.lock = new ReentrantReadWriteLock(true);
        this.dataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
        this.lockDataSource = lockDataSource instanceof HikariDataSource hikari ? hikari : null;
        this.advisoryLockManager = new PostgresAdvisoryLockManager(jdbcLockDataSource, config, this.workspaceId);
        this.exclusiveAdvisoryLockHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);
        try {
            new PostgresSchemaManager(jdbcDataSource, config).bootstrap();
            this.documentStore = new PostgresDocumentStore(jdbcDataSource, config, this.workspaceId);
            this.chunkStore = new PostgresChunkStore(jdbcDataSource, config, this.workspaceId);
            this.graphStore = new PostgresGraphStore(jdbcDataSource, config, this.workspaceId);
            this.vectorStore = new PostgresVectorStore(jdbcDataSource, config, this.workspaceId);
            this.documentStatusStore = new PostgresDocumentStatusStore(jdbcDataSource, config, this.workspaceId);
            this.lockedDocumentStatusStore = new LockedDocumentStatusStore(documentStatusStore);
            this.lockedDocumentStore = new LockedDocumentStore(documentStore);
            this.lockedChunkStore = new LockedChunkStore(chunkStore);
            this.lockedGraphStore = new LockedGraphStore(graphStore);
            this.lockedVectorStore = new LockedVectorStore(vectorStore);
        } catch (RuntimeException exception) {
            closeOwnedDataSources();
            throw exception;
        }
    }

    @Override
    public DocumentStore documentStore() {
        return lockedDocumentStore;
    }

    @Override
    public ChunkStore chunkStore() {
        return lockedChunkStore;
    }

    @Override
    public GraphStore graphStore() {
        return lockedGraphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return lockedVectorStore;
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return lockedDocumentStatusStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return withExclusiveProviderLock(() -> {
            return advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(() -> {
                return PostgresRetrySupport.execute(
                    "atomic write for workspace '%s'".formatted(workspaceId),
                    () -> {
                        try (var connection = jdbcDataSource.getConnection()) {
                            return withTransaction(connection, () -> operation.execute(newAtomicView(connection)));
                        } catch (SQLException exception) {
                            throw new StorageException("Failed to open PostgreSQL transaction", exception);
                        }
                    }
                );
            }));
        });
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        withExclusiveProviderLock(() -> {
            advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(() -> {
                try (var connection = jdbcDataSource.getConnection()) {
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
                        for (var statusRecord : source.documentStatuses()) {
                            stores.documentStatusStore().save(statusRecord);
                        }
                        return null;
                    });
                } catch (SQLException exception) {
                    throw new StorageException("Failed to open PostgreSQL transaction for restore", exception);
                }
                return null;
            }));
            return null;
        });
    }

    @Override
    public void close() {
        closeOwnedDataSources();
    }

    private AtomicStorageView newAtomicView(Connection connection) {
        var connectionAccess = JdbcConnectionAccess.forConnection(connection);
        return new AtomicView(
            new PostgresDocumentStore(connectionAccess, config, workspaceId),
            new PostgresChunkStore(connectionAccess, config, workspaceId),
            new PostgresGraphStore(connectionAccess, config, workspaceId),
            new PostgresVectorStore(connectionAccess, config, workspaceId),
            new PostgresDocumentStatusStore(connectionAccess, config, workspaceId)
        );
    }

    private <T> T withTransaction(Connection connection, SqlSupplier<T> supplier) {
        Throwable primaryFailure = null;
        long startedAtNanos = System.nanoTime();
        try {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = supplier.get();
                connection.commit();
                if (log.isDebugEnabled()) {
                    log.debug(
                        "Committed PostgreSQL transaction for workspace '{}' in {} ms",
                        workspaceId,
                        elapsedMillis(startedAtNanos)
                    );
                }
                return result;
            } catch (RuntimeException | Error failure) {
                primaryFailure = failure;
                rollback(connection, failure);
                throw failure;
            } catch (SQLException exception) {
                primaryFailure = exception;
                rollback(connection, exception);
                throw new StorageException("PostgreSQL transaction failed", exception);
            } finally {
                try {
                    restoreAutoCommit(connection, originalAutoCommit);
                } catch (SQLException exception) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(exception);
                    } else {
                        throw new StorageException("Failed to restore PostgreSQL connection state", exception);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new StorageException("Failed to configure PostgreSQL transaction", exception);
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private void truncateAll(Connection connection) throws SQLException {
        deleteWorkspaceRows(connection, "relation_chunks");
        deleteWorkspaceRows(connection, "entity_chunks");
        deleteWorkspaceRows(connection, "entity_aliases");
        deleteWorkspaceRows(connection, "relations");
        deleteWorkspaceRows(connection, "entities");
        deleteWorkspaceRows(connection, "vectors");
        deleteWorkspaceRows(connection, "chunks");
        deleteWorkspaceRows(connection, "document_status");
        deleteWorkspaceRows(connection, "documents");
    }

    private void deleteWorkspaceRows(Connection connection, String baseTableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + config.qualifiedTableName(baseTableName) + " WHERE workspace_id = ?"
        )) {
            statement.setString(1, workspaceId);
            statement.executeUpdate();
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

    private static HikariDataSource createDataSource(PostgresStorageConfig config, String poolName) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setPoolName(poolName);
        return new HikariDataSource(hikariConfig);
    }

    private void closeOwnedDataSources() {
        if (ownsDataSource && dataSource != null) {
            dataSource.close();
        }
        if (ownsLockDataSource && lockDataSource != null && lockDataSource != dataSource) {
            lockDataSource.close();
        }
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }

    private final class LockedDocumentStore implements DocumentStore {
        private final DocumentStore delegate;

        private LockedDocumentStore(DocumentStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(DocumentRecord document) {
            withWriteLock(() -> delegate.save(document));
        }

        @Override
        public Optional<DocumentRecord> load(String documentId) {
            return withReadLock(() -> delegate.load(documentId));
        }

        @Override
        public List<DocumentRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public boolean contains(String documentId) {
            return withReadLock(() -> delegate.contains(documentId));
        }
    }

    private final class LockedChunkStore implements ChunkStore {
        private final ChunkStore delegate;

        private LockedChunkStore(ChunkStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(ChunkRecord chunk) {
            withWriteLock(() -> delegate.save(chunk));
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            return withReadLock(() -> delegate.load(chunkId));
        }

        @Override
        public List<ChunkRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return withReadLock(() -> delegate.listByDocument(documentId));
        }
    }

    private final class LockedGraphStore implements GraphStore {
        private final GraphStore delegate;

        private LockedGraphStore(GraphStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            withWriteLock(() -> delegate.saveEntity(entity));
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            withWriteLock(() -> delegate.saveRelation(relation));
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return withReadLock(() -> delegate.loadEntity(entityId));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return withReadLock(() -> delegate.loadRelation(relationId));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return withReadLock(delegate::allEntities);
        }

        @Override
        public List<RelationRecord> allRelations() {
            return withReadLock(delegate::allRelations);
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return withReadLock(() -> delegate.findRelations(entityId));
        }
    }

    private final class LockedVectorStore implements VectorStore {
        private final VectorStore delegate;

        private LockedVectorStore(VectorStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            withWriteLock(() -> delegate.saveAll(namespace, vectors));
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return withReadLock(() -> delegate.search(namespace, queryVector, topK));
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return withReadLock(() -> delegate.list(namespace));
        }
    }

    private final class LockedDocumentStatusStore implements DocumentStatusStore {
        private final DocumentStatusStore delegate;

        private LockedDocumentStatusStore(DocumentStatusStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(StatusRecord statusRecord) {
            withWriteLock(() -> delegate.save(statusRecord));
        }

        @Override
        public Optional<StatusRecord> load(String documentId) {
            return withReadLock(() -> delegate.load(documentId));
        }

        @Override
        public List<StatusRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public void delete(String documentId) {
            withWriteLock(() -> delegate.delete(documentId));
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> T withReadLock(RuntimeSupplier<T> supplier) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            if (exclusiveAdvisoryLockHeld.get()) {
                return supplier.get();
            }
            return advisoryLockManager.withSharedLock(supplier::get);
        } finally {
            readLock.unlock();
        }
    }

    private void withWriteLock(Runnable runnable) {
        withExclusiveProviderLock(() -> {
            advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(() -> {
                runnable.run();
                return null;
            }));
            return null;
        });
    }

    private <T> T withExclusiveProviderLock(RuntimeSupplier<T> supplier) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            if (exclusiveAdvisoryLockHeld.get()) {
                return supplier.get();
            }
            return supplier.get();
        } finally {
            writeLock.unlock();
        }
    }

    private <T> T withExclusiveAdvisoryScope(RuntimeSupplier<T> supplier) {
        boolean previous = exclusiveAdvisoryLockHeld.get();
        exclusiveAdvisoryLockHeld.set(true);
        try {
            return supplier.get();
        } finally {
            if (previous) {
                exclusiveAdvisoryLockHeld.set(true);
            } else {
                exclusiveAdvisoryLockHeld.remove();
            }
        }
    }

    @FunctionalInterface
    private interface RuntimeSupplier<T> {
        T get();
    }
}
