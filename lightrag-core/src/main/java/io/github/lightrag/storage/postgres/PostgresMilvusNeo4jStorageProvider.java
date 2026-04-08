package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.indexing.HybridVectorPayloads;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import io.github.lightrag.storage.milvus.MilvusVectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphConfig;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import io.github.lightrag.storage.neo4j.WorkspaceScopedNeo4jGraphStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.sql.DataSource;

public final class PostgresMilvusNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");
    private static final List<String> VECTOR_NAMESPACES = List.of("chunks", "entities", "relations");

    private final ReentrantReadWriteLock lock;
    private final DataSource jdbcDataSource;
    private final HikariDataSource ownedDataSource;
    private final boolean ownsDataSource;
    private final SnapshotStore snapshotStore;
    private final PostgresStorageConfig postgresConfig;
    private final String workspaceId;
    private final PostgresAdvisoryLockManager advisoryLockManager;
    private final ThreadLocal<Boolean> exclusiveAdvisoryLockHeld;
    private final DocumentStore documentStore;
    private final ChunkStore chunkStore;
    private final GraphStore postgresGraphStore;
    private final DocumentStatusStore documentStatusStore;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final VectorStore lockedVectorStore;
    private final GraphProjection graphProjection;
    private final VectorProjection vectorProjection;
    private final GraphStore graphStore;

    public PostgresMilvusNeo4jStorageProvider(
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(postgresConfig, milvusConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(dataSource, postgresConfig, milvusConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public PostgresMilvusNeo4jStorageProvider(
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            createDataSource(Objects.requireNonNull(postgresConfig, "postgresConfig"), "lightrag-postgres"),
            true,
            postgresConfig,
            snapshotStore,
            workspaceScope,
            new Neo4jGraphProjection(new WorkspaceScopedNeo4jGraphStore(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                Objects.requireNonNull(workspaceScope, "workspaceScope")
            )),
            new MilvusVectorProjection(new MilvusVectorStore(
                Objects.requireNonNull(milvusConfig, "milvusConfig"),
                workspaceScope.workspaceId()
            )),
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            dataSource,
            false,
            postgresConfig,
            snapshotStore,
            workspaceScope,
            new Neo4jGraphProjection(new WorkspaceScopedNeo4jGraphStore(
                Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                Objects.requireNonNull(workspaceScope, "workspaceScope")
            )),
            new MilvusVectorProjection(new MilvusVectorStore(
                Objects.requireNonNull(milvusConfig, "milvusConfig"),
                workspaceScope.workspaceId()
            )),
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection
    ) {
        this(
            dataSource,
            postgresConfig,
            snapshotStore,
            workspaceScope,
            graphProjection,
            vectorProjection,
            new ReentrantReadWriteLock(true)
        );
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection,
        ReentrantReadWriteLock lock
    ) {
        this(
            dataSource,
            false,
            postgresConfig,
            snapshotStore,
            workspaceScope,
            graphProjection,
            vectorProjection,
            lock
        );
    }

    private PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        boolean ownsDataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection,
        ReentrantReadWriteLock lock
    ) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.jdbcDataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.ownedDataSource = dataSource instanceof HikariDataSource hikari ? hikari : null;
        this.ownsDataSource = ownsDataSource;
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        var resolvedConfig = ownsDataSource
            ? Objects.requireNonNull(postgresConfig, "postgresConfig")
            : PostgresSchemaResolver.alignWithDataSourceSchema(
                dataSource,
                Objects.requireNonNull(postgresConfig, "postgresConfig")
            );
        this.postgresConfig = resolvedConfig;
        this.workspaceId = Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId();
        this.graphProjection = Objects.requireNonNull(graphProjection, "graphProjection");
        this.vectorProjection = Objects.requireNonNull(vectorProjection, "vectorProjection");
        this.advisoryLockManager = new PostgresAdvisoryLockManager(jdbcDataSource, resolvedConfig, this.workspaceId);
        this.exclusiveAdvisoryLockHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);
        try {
            new PostgresMilvusNeo4jSchemaManager(jdbcDataSource, resolvedConfig).bootstrap();
            this.documentStore = new PostgresDocumentStore(jdbcDataSource, resolvedConfig, workspaceId);
            this.chunkStore = new PostgresChunkStore(jdbcDataSource, resolvedConfig, workspaceId);
            this.postgresGraphStore = new PostgresGraphStore(jdbcDataSource, resolvedConfig, workspaceId);
            this.documentStatusStore = new PostgresDocumentStatusStore(jdbcDataSource, resolvedConfig, workspaceId);
            this.lockedDocumentStore = new LockedDocumentStore(documentStore);
            this.lockedChunkStore = new LockedChunkStore(chunkStore);
            this.lockedDocumentStatusStore = new LockedDocumentStatusStore(documentStatusStore);
            this.lockedVectorStore = new LockedVectorStore(vectorProjection);
            this.graphStore = new MirroringGraphStore();
        } catch (RuntimeException exception) {
            closeQuietly(vectorProjection, exception);
            closeQuietly(graphProjection, exception);
            closeOwnedResources();
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
        return graphStore;
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
        lock.writeLock().lock();
        try {
            SnapshotStore.Snapshot beforePostgres = capturePostgresSnapshot();
            Neo4jGraphSnapshot beforeNeo4j = graphProjection.captureSnapshot();
            Map<String, List<VectorStore.VectorRecord>> beforeMilvus = captureMilvusVectors();
            Map<String, GraphStore.EntityRecord> stagedEntities = new LinkedHashMap<>();
            Map<String, GraphStore.RelationRecord> stagedRelations = new LinkedHashMap<>();
            SnapshotBackedHybridVectorStore stagedVectorStore =
                new SnapshotBackedHybridVectorStore(snapshotWithVectors(beforePostgres, beforeMilvus));
            try {
                T result = executeExclusiveTransaction(
                    "atomic write for workspace '%s'".formatted(workspaceId),
                    (connection, connectionAccess) -> operation.execute(new AtomicView(
                        new PostgresDocumentStore(connectionAccess, postgresConfig, workspaceId),
                        new PostgresChunkStore(connectionAccess, postgresConfig, workspaceId),
                        new ProjectionStagingGraphStore(
                            new PostgresGraphStore(connectionAccess, postgresConfig, workspaceId),
                            stagedEntities,
                            stagedRelations
                        ),
                        stagedVectorStore,
                        new PostgresDocumentStatusStore(connectionAccess, postgresConfig, workspaceId)
                    ))
                );
                applyGraphProjection(stagedEntities.values(), stagedRelations.values());
                applyMilvusSnapshot(capturePostgresSnapshot(), stagedVectorStore.namespaceRecords());
                return result;
            } catch (RuntimeException failure) {
                restoreSnapshots(beforePostgres, beforeNeo4j, beforeMilvus, failure);
                throw failure;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        SnapshotStore.Snapshot replacement = Objects.requireNonNull(snapshot, "snapshot");
        lock.writeLock().lock();
        try {
            SnapshotStore.Snapshot beforePostgres = capturePostgresSnapshot();
            Neo4jGraphSnapshot beforeNeo4j = graphProjection.captureSnapshot();
            Map<String, List<VectorStore.VectorRecord>> beforeMilvus = captureMilvusVectors();
            try {
                restorePostgresSnapshot(replacement);
                graphProjection.restore(new Neo4jGraphSnapshot(replacement.entities(), replacement.relations()));
                restoreMilvusSnapshot(replacement);
            } catch (RuntimeException failure) {
                restoreSnapshots(beforePostgres, beforeNeo4j, beforeMilvus, failure);
                throw failure;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            graphProjection.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            vectorProjection.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        try {
            closeOwnedResources();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private SnapshotStore.Snapshot capturePostgresSnapshot() {
        return new SnapshotStore.Snapshot(
            documentStore.list(),
            chunkStore.list(),
            postgresGraphStore.allEntities(),
            postgresGraphStore.allRelations(),
            Map.of(),
            documentStatusStore.list()
        );
    }

    private Map<String, List<VectorStore.VectorRecord>> captureMilvusVectors() {
        return Map.of(
            "chunks", vectorProjection.list("chunks"),
            "entities", vectorProjection.list("entities"),
            "relations", vectorProjection.list("relations")
        );
    }

    private void applyGraphProjection(
        java.util.Collection<GraphStore.EntityRecord> entities,
        java.util.Collection<GraphStore.RelationRecord> relations
    ) {
        for (GraphStore.EntityRecord entity : entities) {
            graphProjection.saveEntity(entity);
        }
        for (GraphStore.RelationRecord relation : relations) {
            graphProjection.saveRelation(relation);
        }
    }

    private void applyMilvusSnapshot(
        SnapshotStore.Snapshot postgresSnapshot,
        Map<String, List<HybridVectorStore.EnrichedVectorRecord>> namespaceRecords
    ) {
        for (String namespace : VECTOR_NAMESPACES) {
            vectorProjection.deleteNamespace(namespace);
            List<HybridVectorStore.EnrichedVectorRecord> records = namespaceRecords.getOrDefault(namespace, List.of());
            if (!records.isEmpty()) {
                vectorProjection.saveAllEnriched(namespace, records);
            }
        }
        vectorProjection.flushNamespaces(VECTOR_NAMESPACES);
    }

    private void restoreMilvusSnapshot(SnapshotStore.Snapshot snapshot) {
        applyMilvusSnapshot(snapshot, buildEnrichedVectors(snapshot));
    }

    private Map<String, List<HybridVectorStore.EnrichedVectorRecord>> buildEnrichedVectors(SnapshotStore.Snapshot snapshot) {
        return Map.of(
            "chunks", HybridVectorPayloads.chunkPayloads(
                snapshot.chunks().stream().map(PostgresMilvusNeo4jStorageProvider::toChunk).toList(),
                snapshot.vectors().getOrDefault("chunks", List.of())
            ),
            "entities", HybridVectorPayloads.entityPayloads(
                snapshot.entities().stream().map(PostgresMilvusNeo4jStorageProvider::toEntity).toList(),
                snapshot.vectors().getOrDefault("entities", List.of())
            ),
            "relations", HybridVectorPayloads.relationPayloads(
                snapshot.relations().stream().map(PostgresMilvusNeo4jStorageProvider::toRelation).toList(),
                snapshot.vectors().getOrDefault("relations", List.of())
            )
        );
    }

    private void restorePostgresSnapshot(SnapshotStore.Snapshot snapshot) {
        executeExclusiveTransaction(
            "restore postgres rows for workspace '%s'".formatted(workspaceId),
            (connection, connectionAccess) -> {
                truncateAll(connection);
                DocumentStore documentStore = new PostgresDocumentStore(connectionAccess, postgresConfig, workspaceId);
                ChunkStore chunkStore = new PostgresChunkStore(connectionAccess, postgresConfig, workspaceId);
                GraphStore graphStore = new PostgresGraphStore(connectionAccess, postgresConfig, workspaceId);
                DocumentStatusStore statusStore = new PostgresDocumentStatusStore(connectionAccess, postgresConfig, workspaceId);
                for (DocumentStore.DocumentRecord document : snapshot.documents()) {
                    documentStore.save(document);
                }
                for (ChunkStore.ChunkRecord chunk : snapshot.chunks()) {
                    chunkStore.save(chunk);
                }
                for (GraphStore.EntityRecord entity : snapshot.entities()) {
                    graphStore.saveEntity(entity);
                }
                for (GraphStore.RelationRecord relation : snapshot.relations()) {
                    graphStore.saveRelation(relation);
                }
                for (DocumentStatusStore.StatusRecord status : snapshot.documentStatuses()) {
                    statusStore.save(status);
                }
                return null;
            }
        );
    }

    private void restoreSnapshots(
        SnapshotStore.Snapshot beforePostgres,
        Neo4jGraphSnapshot beforeNeo4j,
        Map<String, List<VectorStore.VectorRecord>> beforeMilvus,
        RuntimeException failure
    ) {
        try {
            restorePostgresSnapshot(beforePostgres);
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
        try {
            graphProjection.restore(beforeNeo4j);
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
        try {
            restoreMilvusSnapshot(snapshotWithVectors(beforePostgres, beforeMilvus));
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
    }

    private SnapshotStore.Snapshot snapshotWithVectors(
        SnapshotStore.Snapshot base,
        Map<String, List<VectorStore.VectorRecord>> vectors
    ) {
        return new SnapshotStore.Snapshot(
            base.documents(),
            base.chunks(),
            base.entities(),
            base.relations(),
            vectors,
            base.documentStatuses()
        );
    }

    private <T> T executeExclusiveTransaction(String operationName, TransactionOperation<T> operation) {
        return advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(() -> PostgresRetrySupport.execute(
            operationName,
            () -> {
                try (Connection connection = jdbcDataSource.getConnection()) {
                    JdbcConnectionAccess connectionAccess = JdbcConnectionAccess.forConnection(connection);
                    return withTransaction(connection, () -> operation.execute(connection, connectionAccess));
                } catch (SQLException exception) {
                    throw new StorageException("Failed to open PostgreSQL transaction", exception);
                }
            }
        )));
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

    private void truncateAll(Connection connection) throws SQLException {
        deleteWorkspaceRows(connection, "relation_chunks");
        deleteWorkspaceRows(connection, "entity_chunks");
        deleteWorkspaceRows(connection, "entity_aliases");
        deleteWorkspaceRows(connection, "relations");
        deleteWorkspaceRows(connection, "entities");
        deleteWorkspaceRows(connection, "chunks");
        deleteWorkspaceRows(connection, "document_status");
        deleteWorkspaceRows(connection, "documents");
    }

    private void deleteWorkspaceRows(Connection connection, String baseTableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + postgresConfig.qualifiedTableName(baseTableName) + " WHERE workspace_id = ?"
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

    private void closeOwnedResources() {
        if (ownsDataSource && ownedDataSource != null) {
            ownedDataSource.close();
        }
    }

    private static void closeQuietly(AutoCloseable closeable, RuntimeException failure) {
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            addSuppressedIfDistinct(failure, closeFailure);
        }
    }

    private static void addSuppressedIfDistinct(Throwable target, Throwable suppressed) {
        if (target != suppressed) {
            target.addSuppressed(suppressed);
        }
    }

    private static io.github.lightrag.types.Entity toEntity(GraphStore.EntityRecord entityRecord) {
        return new io.github.lightrag.types.Entity(
            entityRecord.id(),
            entityRecord.name(),
            entityRecord.type(),
            entityRecord.description(),
            entityRecord.aliases(),
            entityRecord.sourceChunkIds()
        );
    }

    private static io.github.lightrag.types.Relation toRelation(GraphStore.RelationRecord relationRecord) {
        return new io.github.lightrag.types.Relation(
            relationRecord.id(),
            relationRecord.sourceEntityId(),
            relationRecord.targetEntityId(),
            relationRecord.type(),
            relationRecord.description(),
            relationRecord.weight(),
            relationRecord.sourceChunkIds()
        );
    }

    private static io.github.lightrag.types.Chunk toChunk(ChunkStore.ChunkRecord chunkRecord) {
        return new io.github.lightrag.types.Chunk(
            chunkRecord.id(),
            chunkRecord.documentId(),
            chunkRecord.text(),
            chunkRecord.tokenCount(),
            chunkRecord.order(),
            chunkRecord.metadata()
        );
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

    public interface GraphProjection extends GraphStore, AutoCloseable {
        Neo4jGraphSnapshot captureSnapshot();

        void restore(Neo4jGraphSnapshot snapshot);

        @Override
        void close();
    }

    public interface VectorProjection extends HybridVectorStore, AutoCloseable {
        void deleteNamespace(String namespace);

        void flushNamespaces(List<String> namespaces);

        @Override
        void close();
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }

    private final class MirroringGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            mirrorWrite(entity, null);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            mirrorWrite(null, relation);
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return withReadLock(() -> graphProjection.loadEntity(entityId));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return withReadLock(() -> graphProjection.loadRelation(relationId));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return withReadLock(graphProjection::allEntities);
        }

        @Override
        public List<RelationRecord> allRelations() {
            return withReadLock(graphProjection::allRelations);
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return withReadLock(() -> graphProjection.findRelations(entityId));
        }

        private void mirrorWrite(EntityRecord entity, RelationRecord relation) {
            lock.writeLock().lock();
            try {
                SnapshotStore.Snapshot beforePostgres = capturePostgresSnapshot();
                Neo4jGraphSnapshot beforeNeo4j = graphProjection.captureSnapshot();
                Map<String, List<VectorStore.VectorRecord>> beforeMilvus = captureMilvusVectors();
                try {
                    executeExclusiveTransaction(
                        "mirror graph write for workspace '%s'".formatted(workspaceId),
                        (connection, connectionAccess) -> {
                            GraphStore postgresGraphStore = new PostgresGraphStore(connectionAccess, postgresConfig, workspaceId);
                            if (entity != null) {
                                postgresGraphStore.saveEntity(entity);
                            }
                            if (relation != null) {
                                postgresGraphStore.saveRelation(relation);
                            }
                            return null;
                        }
                    );
                    if (entity != null) {
                        applyGraphProjection(List.of(entity), List.of());
                    }
                    if (relation != null) {
                        applyGraphProjection(List.of(), List.of(relation));
                    }
                } catch (RuntimeException failure) {
                    restoreSnapshots(beforePostgres, beforeNeo4j, beforeMilvus, failure);
                    throw failure;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
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

    private static final class ProjectionStagingGraphStore implements GraphStore {
        private final GraphStore postgresGraphStore;
        private final Map<String, EntityRecord> stagedEntities;
        private final Map<String, RelationRecord> stagedRelations;

        private ProjectionStagingGraphStore(
            GraphStore postgresGraphStore,
            Map<String, EntityRecord> stagedEntities,
            Map<String, RelationRecord> stagedRelations
        ) {
            this.postgresGraphStore = Objects.requireNonNull(postgresGraphStore, "postgresGraphStore");
            this.stagedEntities = Objects.requireNonNull(stagedEntities, "stagedEntities");
            this.stagedRelations = Objects.requireNonNull(stagedRelations, "stagedRelations");
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            postgresGraphStore.saveEntity(entity);
            stagedEntities.put(entity.id(), entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            postgresGraphStore.saveRelation(relation);
            stagedRelations.put(relation.id(), relation);
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return postgresGraphStore.loadEntity(entityId);
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return postgresGraphStore.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return postgresGraphStore.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return postgresGraphStore.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return postgresGraphStore.findRelations(entityId);
        }
    }

    private static final class SnapshotBackedHybridVectorStore implements HybridVectorStore {
        private final Map<String, LinkedHashMap<String, EnrichedVectorRecord>> namespaceRecords;

        private SnapshotBackedHybridVectorStore(SnapshotStore.Snapshot snapshot) {
            this.namespaceRecords = new LinkedHashMap<>();
            seedNamespace("chunks", HybridVectorPayloads.chunkPayloads(
                snapshot.chunks().stream().map(PostgresMilvusNeo4jStorageProvider::toChunk).toList(),
                snapshot.vectors().getOrDefault("chunks", List.of())
            ));
            seedNamespace("entities", HybridVectorPayloads.entityPayloads(
                snapshot.entities().stream().map(PostgresMilvusNeo4jStorageProvider::toEntity).toList(),
                snapshot.vectors().getOrDefault("entities", List.of())
            ));
            seedNamespace("relations", HybridVectorPayloads.relationPayloads(
                snapshot.relations().stream().map(PostgresMilvusNeo4jStorageProvider::toRelation).toList(),
                snapshot.vectors().getOrDefault("relations", List.of())
            ));
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            saveAllEnriched(namespace, vectors.stream()
                .map(vector -> new EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
                .toList());
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return inMemoryView().search(namespace, queryVector, topK);
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return namespace(namespace).values().stream()
                .map(EnrichedVectorRecord::toVectorRecord)
                .sorted(java.util.Comparator.comparing(VectorRecord::id))
                .toList();
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
            LinkedHashMap<String, EnrichedVectorRecord> target = namespace(namespace);
            for (EnrichedVectorRecord record : records) {
                target.put(record.id(), record);
            }
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return inMemoryView().search(namespace, request);
        }

        private Map<String, List<EnrichedVectorRecord>> namespaceRecords() {
            LinkedHashMap<String, List<EnrichedVectorRecord>> copied = new LinkedHashMap<>();
            for (String namespace : VECTOR_NAMESPACES) {
                copied.put(namespace, List.copyOf(namespace(namespace).values()));
            }
            return Map.copyOf(copied);
        }

        private void seedNamespace(String namespace, List<EnrichedVectorRecord> records) {
            LinkedHashMap<String, EnrichedVectorRecord> values = namespace(namespace);
            for (EnrichedVectorRecord record : records) {
                values.put(record.id(), record);
            }
        }

        private LinkedHashMap<String, EnrichedVectorRecord> namespace(String namespace) {
            return namespaceRecords.computeIfAbsent(Objects.requireNonNull(namespace, "namespace"), ignored -> new LinkedHashMap<>());
        }

        private io.github.lightrag.storage.memory.InMemoryVectorStore inMemoryView() {
            io.github.lightrag.storage.memory.InMemoryVectorStore store = new io.github.lightrag.storage.memory.InMemoryVectorStore();
            for (Map.Entry<String, LinkedHashMap<String, EnrichedVectorRecord>> entry : namespaceRecords.entrySet()) {
                store.saveAllEnriched(entry.getKey(), List.copyOf(entry.getValue().values()));
            }
            return store;
        }
    }

    private static final class Neo4jGraphProjection implements GraphProjection {
        private final WorkspaceScopedNeo4jGraphStore delegate;

        private Neo4jGraphProjection(WorkspaceScopedNeo4jGraphStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveEntity(GraphStore.EntityRecord entity) {
            delegate.saveEntity(entity);
        }

        @Override
        public void saveRelation(GraphStore.RelationRecord relation) {
            delegate.saveRelation(relation);
        }

        @Override
        public Optional<GraphStore.EntityRecord> loadEntity(String entityId) {
            return delegate.loadEntity(entityId);
        }

        @Override
        public Optional<GraphStore.RelationRecord> loadRelation(String relationId) {
            return delegate.loadRelation(relationId);
        }

        @Override
        public List<GraphStore.EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public List<GraphStore.RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public List<GraphStore.RelationRecord> findRelations(String entityId) {
            return delegate.findRelations(entityId);
        }

        @Override
        public Neo4jGraphSnapshot captureSnapshot() {
            return delegate.captureSnapshot();
        }

        @Override
        public void restore(Neo4jGraphSnapshot snapshot) {
            delegate.restore(snapshot);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class MilvusVectorProjection implements VectorProjection {
        private final MilvusVectorStore delegate;

        private MilvusVectorProjection(MilvusVectorStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            delegate.saveAll(namespace, vectors);
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return delegate.search(namespace, queryVector, topK);
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return delegate.list(namespace);
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
            delegate.saveAllEnriched(namespace, records);
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return delegate.search(namespace, request);
        }

        @Override
        public void deleteNamespace(String namespace) {
            delegate.deleteNamespace(namespace);
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            delegate.flushNamespaces(namespaces);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class PostgresMilvusNeo4jSchemaManager {
        private final DataSource dataSource;
        private final PostgresStorageConfig config;

        private PostgresMilvusNeo4jSchemaManager(DataSource dataSource, PostgresStorageConfig config) {
            this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
            this.config = Objects.requireNonNull(config, "config");
        }

        private void bootstrap() {
            try (Connection connection = dataSource.getConnection()) {
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
                    for (String sql : statements()) {
                        statement.execute(sql);
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
                } finally {
                    restoreAutoCommit(connection, originalAutoCommit);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to bootstrap PostgreSQL schema", exception);
            }
        }

        private List<String> statements() {
            return List.of(
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """.formatted(config.qualifiedTableName("documents")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        document_id TEXT NOT NULL,
                        text TEXT NOT NULL,
                        token_count INTEGER NOT NULL,
                        chunk_order INTEGER NOT NULL,
                        metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """.formatted(config.qualifiedTableName("chunks")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        description TEXT NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """.formatted(config.qualifiedTableName("entities")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        alias TEXT NOT NULL,
                        PRIMARY KEY (workspace_id, entity_id, alias)
                    )
                    """.formatted(config.qualifiedTableName("entity_aliases")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        chunk_id TEXT NOT NULL,
                        PRIMARY KEY (workspace_id, entity_id, chunk_id)
                    )
                    """.formatted(config.qualifiedTableName("entity_chunks")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        id TEXT NOT NULL,
                        source_entity_id TEXT NOT NULL,
                        target_entity_id TEXT NOT NULL,
                        type TEXT NOT NULL,
                        description TEXT NOT NULL,
                        weight DOUBLE PRECISION NOT NULL,
                        PRIMARY KEY (workspace_id, id)
                    )
                    """.formatted(config.qualifiedTableName("relations")),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        relation_id TEXT NOT NULL,
                        chunk_id TEXT NOT NULL,
                        PRIMARY KEY (workspace_id, relation_id, chunk_id)
                    )
                    """.formatted(config.qualifiedTableName("relation_chunks")),
                dropConstraintSql("chunks", config.tableName("chunks") + "_document_id_fkey"),
                dropConstraintSql("entity_aliases", config.tableName("entity_aliases") + "_entity_id_fkey"),
                dropConstraintSql("entity_chunks", config.tableName("entity_chunks") + "_entity_id_fkey"),
                dropConstraintSql("entity_chunks", config.tableName("entity_chunks") + "_chunk_id_fkey"),
                dropConstraintSql("relations", config.tableName("relations") + "_source_entity_id_fkey"),
                dropConstraintSql("relations", config.tableName("relations") + "_target_entity_id_fkey"),
                dropConstraintSql("relation_chunks", config.tableName("relation_chunks") + "_relation_id_fkey"),
                dropConstraintSql("relation_chunks", config.tableName("relation_chunks") + "_chunk_id_fkey"),
                """
                    CREATE TABLE IF NOT EXISTS %s (
                        workspace_id TEXT NOT NULL,
                        document_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        summary TEXT NOT NULL DEFAULT '',
                        error_message TEXT,
                        PRIMARY KEY (workspace_id, document_id)
                    )
                    """.formatted(config.qualifiedTableName("document_status"))
            );
        }

        private String dropConstraintSql(String tableName, String constraintName) {
            return """
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM information_schema.table_constraints
                        WHERE table_schema = '%s'
                          AND table_name = '%s'
                          AND constraint_name = '%s'
                    ) THEN
                        EXECUTE 'ALTER TABLE %s DROP CONSTRAINT %s';
                    END IF;
                END $$;
                """.formatted(
                config.schema(),
                config.tableName(tableName),
                constraintName,
                config.qualifiedTableName(tableName),
                constraintName
            );
        }
    }

    @FunctionalInterface
    private interface TransactionOperation<T> {
        T execute(Connection connection, JdbcConnectionAccess connectionAccess) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface RuntimeSupplier<T> {
        T get();
    }
}
