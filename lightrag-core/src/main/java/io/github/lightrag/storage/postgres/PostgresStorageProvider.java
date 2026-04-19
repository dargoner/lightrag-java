package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
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
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentSkipListSet;
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
    private final TaskStore taskStore;
    private final TaskStageStore taskStageStore;
    private final TaskDocumentStore taskDocumentStore;
    private final PostgresDocumentStore documentStore;
    private final PostgresChunkStore chunkStore;
    private final PostgresGraphStore graphStore;
    private final PostgresVectorStore vectorStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final TaskStore lockedTaskStore;
    private final TaskStageStore lockedTaskStageStore;
    private final TaskDocumentStore lockedTaskDocumentStore;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final GraphStore lockedGraphStore;
    private final VectorStore lockedVectorStore;
    private final DocumentGraphSnapshotStore documentGraphSnapshotStore;
    private final DocumentGraphJournalStore documentGraphJournalStore;
    private final java.util.Set<String> trackedDocumentGraphIds;
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
        var resolvedConfig = ownsDataSource
            ? Objects.requireNonNull(config, "config")
            : PostgresSchemaResolver.alignWithDataSourceSchema(
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(config, "config")
            );
        this.config = resolvedConfig;
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.workspaceId = new WorkspaceScope(workspaceId).workspaceId();
        this.jdbcDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcLockDataSource = Objects.requireNonNull(lockDataSource, "lockDataSource");
        this.ownsDataSource = ownsDataSource;
        this.ownsLockDataSource = ownsLockDataSource;
        this.lock = new ReentrantReadWriteLock(true);
        this.dataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
        this.lockDataSource = lockDataSource instanceof HikariDataSource hikari ? hikari : null;
        this.advisoryLockManager = new PostgresAdvisoryLockManager(jdbcLockDataSource, resolvedConfig, this.workspaceId);
        this.exclusiveAdvisoryLockHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);
        this.trackedDocumentGraphIds = new ConcurrentSkipListSet<>();
        try {
            new PostgresSchemaManager(jdbcDataSource, resolvedConfig).bootstrap();
            this.documentStore = new PostgresDocumentStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.chunkStore = new PostgresChunkStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.graphStore = new PostgresGraphStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.vectorStore = new PostgresVectorStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.documentStatusStore = new PostgresDocumentStatusStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.taskStore = new PostgresTaskStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.taskStageStore = new PostgresTaskStageStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.taskDocumentStore = new PostgresTaskDocumentStore(jdbcDataSource, resolvedConfig, this.workspaceId);
            this.documentGraphSnapshotStore = DocumentGraphStateSupport.trackedSnapshotStore(
                new PostgresDocumentGraphSnapshotStore(jdbcDataSource, resolvedConfig, this.workspaceId),
                trackedDocumentGraphIds
            );
            this.documentGraphJournalStore = DocumentGraphStateSupport.trackedJournalStore(
                new PostgresDocumentGraphJournalStore(jdbcDataSource, resolvedConfig, this.workspaceId),
                trackedDocumentGraphIds
            );
            this.lockedDocumentStatusStore = new LockedDocumentStatusStore(documentStatusStore);
            this.lockedTaskStore = new LockedTaskStore(taskStore);
            this.lockedTaskStageStore = new LockedTaskStageStore(taskStageStore);
            this.lockedTaskDocumentStore = new LockedTaskDocumentStore(taskDocumentStore);
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
    public TaskStore taskStore() {
        return lockedTaskStore;
    }

    @Override
    public TaskStageStore taskStageStore() {
        return lockedTaskStageStore;
    }

    @Override
    public TaskDocumentStore taskDocumentStore() {
        return lockedTaskDocumentStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        return documentGraphSnapshotStore;
    }

    @Override
    public DocumentGraphJournalStore documentGraphJournalStore() {
        return documentGraphJournalStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return withWorkspaceExclusiveLock(() -> {
            var documentGraphState = captureDocumentGraphState();
            return PostgresRetrySupport.execute(
                "atomic write for workspace '%s'".formatted(workspaceId),
                () -> {
                    try (var connection = jdbcDataSource.getConnection()) {
                        return withTransaction(connection, () -> operation.execute(newAtomicView(connection)));
                    } catch (SQLException exception) {
                        throw new StorageException("Failed to open PostgreSQL transaction", exception);
                    } catch (RuntimeException | Error failure) {
                        restoreDocumentGraphState(documentGraphState, failure);
                        throw failure;
                    }
                }
            );
        });
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        withWorkspaceExclusiveLock(() -> {
            var previousDocumentGraphState = captureDocumentGraphState();
            try {
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
                        DocumentGraphStateSupport.restore(
                            stores.documentGraphSnapshotStore(),
                            stores.documentGraphJournalStore(),
                            java.util.Set.of(),
                            source
                        );
                        return null;
                    });
                }
            } catch (SQLException exception) {
                restoreDocumentGraphState(previousDocumentGraphState, exception);
                throw new StorageException("Failed to open PostgreSQL transaction for restore", exception);
            } catch (RuntimeException | Error failure) {
                restoreDocumentGraphState(previousDocumentGraphState, failure);
                throw failure;
            }
            return null;
        });
    }

    public <T> T withWorkspaceSharedLock(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return withSharedWorkspaceLock(supplier::get);
    }

    public <T> T withWorkspaceExclusiveLock(Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return withExclusiveWorkspaceLock(supplier::get);
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
            new PostgresDocumentGraphSnapshotStore(connectionAccess, config, workspaceId),
            new PostgresDocumentGraphJournalStore(connectionAccess, config, workspaceId),
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

    private SnapshotStore.DocumentGraphState captureDocumentGraphState() {
        return DocumentGraphStateSupport.capture(
            documentGraphSnapshotStore,
            documentGraphJournalStore,
            trackedDocumentGraphIds,
            documentStore.list(),
            documentStatusStore.list()
        );
    }

    private void restoreDocumentGraphState(SnapshotStore.DocumentGraphState state, Throwable failure) {
        try {
            DocumentGraphStateSupport.restore(
                documentGraphSnapshotStore,
                documentGraphJournalStore,
                trackedDocumentGraphIds,
                new SnapshotStore.Snapshot(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    state.documentSnapshots(),
                    state.chunkSnapshots(),
                    state.documentJournals(),
                    state.chunkJournals()
                )
            );
        } catch (RuntimeException restoreFailure) {
            failure.addSuppressed(restoreFailure);
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
        deleteWorkspaceRows(connection, "chunk_graph_journals");
        deleteWorkspaceRows(connection, "document_graph_journals");
        deleteWorkspaceRows(connection, "chunk_graph_snapshots");
        deleteWorkspaceRows(connection, "document_graph_snapshots");
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
        DocumentGraphSnapshotStore documentGraphSnapshotStore,
        DocumentGraphJournalStore documentGraphJournalStore,
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

    private final class LockedTaskStore implements TaskStore {
        private final TaskStore delegate;

        private LockedTaskStore(TaskStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(TaskRecord taskRecord) {
            withWriteLock(() -> delegate.save(taskRecord));
        }

        @Override
        public Optional<TaskRecord> load(String taskId) {
            return withReadLock(() -> delegate.load(taskId));
        }

        @Override
        public List<TaskRecord> list() {
            return withReadLock(delegate::list);
        }

        @Override
        public void delete(String taskId) {
            withWriteLock(() -> delegate.delete(taskId));
        }
    }

    private final class LockedTaskStageStore implements TaskStageStore {
        private final TaskStageStore delegate;

        private LockedTaskStageStore(TaskStageStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void save(TaskStageRecord taskStageRecord) {
            withWriteLock(() -> delegate.save(taskStageRecord));
        }

        @Override
        public List<TaskStageRecord> listByTask(String taskId) {
            return withReadLock(() -> delegate.listByTask(taskId));
        }

        @Override
        public void deleteByTask(String taskId) {
            withWriteLock(() -> delegate.deleteByTask(taskId));
        }
    }

    private final class LockedTaskDocumentStore implements TaskDocumentStore {
        private final TaskDocumentStore delegate;

        private LockedTaskDocumentStore(TaskDocumentStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void save(TaskDocumentRecord record) {
            withWriteLock(() -> delegate.save(record));
        }

        @Override
        public Optional<TaskDocumentRecord> load(String taskId, String documentId) {
            return withReadLock(() -> delegate.load(taskId, documentId));
        }

        @Override
        public List<TaskDocumentRecord> listByTask(String taskId) {
            return withReadLock(() -> delegate.listByTask(taskId));
        }

        @Override
        public void deleteByTask(String taskId) {
            withWriteLock(() -> delegate.deleteByTask(taskId));
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private <T> T withReadLock(RuntimeSupplier<T> supplier) {
        return withSharedWorkspaceLock(supplier);
    }

    private <T> T withSharedWorkspaceLock(RuntimeSupplier<T> supplier) {
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
        withExclusiveWorkspaceLock(() -> {
            runnable.run();
            return null;
        });
    }

    private <T> T withExclusiveWorkspaceLock(RuntimeSupplier<T> supplier) {
        return withExclusiveProviderLock(() -> {
            if (exclusiveAdvisoryLockHeld.get()) {
                return supplier.get();
            }
            return advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(supplier));
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
