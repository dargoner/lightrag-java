package io.github.lightrag.storage.mysql;

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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class MySqlMilvusNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final int DEFAULT_POOL_SIZE = 4;
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");
    private static final List<String> VECTOR_NAMESPACES = List.of("chunks", "entities", "relations");

    private final ReentrantReadWriteLock lock;
    private final DataSource jdbcDataSource;
    private final HikariDataSource ownedDataSource;
    private final boolean ownsDataSource;
    private final SnapshotStore snapshotStore;
    private final MySqlStorageConfig mySqlConfig;
    private final String workspaceId;
    private final MySqlNamedLockManager namedLockManager;
    private final MySqlDocumentStore documentStore;
    private final MySqlChunkStore chunkStore;
    private final MySqlDocumentStatusStore documentStatusStore;
    private final GraphProjection graphProjection;
    private final VectorProjection vectorProjection;
    private final GraphStore graphStore;

    public MySqlMilvusNeo4jStorageProvider(
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(mySqlConfig, milvusConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public MySqlMilvusNeo4jStorageProvider(
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            createDataSource(Objects.requireNonNull(mySqlConfig, "mySqlConfig"), "lightrag-mysql"),
            true,
            mySqlConfig,
            milvusConfig,
            neo4jConfig,
            snapshotStore,
            workspaceScope
        );
    }

    public MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore
    ) {
        this(dataSource, mySqlConfig, milvusConfig, neo4jConfig, snapshotStore, DEFAULT_WORKSPACE);
    }

    public MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(dataSource, false, mySqlConfig, milvusConfig, neo4jConfig, snapshotStore, workspaceScope);
    }

    private MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        boolean ownsDataSource,
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(
            dataSource,
            ownsDataSource,
            mySqlConfig,
            snapshotStore,
            workspaceScope,
            new Neo4jGraphProjection(
                new WorkspaceScopedNeo4jGraphStore(
                    Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
                    workspaceScope
                )
            ),
            new MilvusVectorProjection(
                new MilvusVectorStore(
                    Objects.requireNonNull(milvusConfig, "milvusConfig"),
                    Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId()
                )
            ),
            new ReentrantReadWriteLock(true)
        );
    }

    public MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection
    ) {
        this(
            dataSource,
            mySqlConfig,
            snapshotStore,
            workspaceScope,
            graphProjection,
            vectorProjection,
            new ReentrantReadWriteLock(true)
        );
    }

    public MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection,
        ReentrantReadWriteLock lock
    ) {
        this(
            dataSource,
            false,
            mySqlConfig,
            snapshotStore,
            workspaceScope,
            graphProjection,
            vectorProjection,
            lock
        );
    }

    private MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        boolean ownsDataSource,
        MySqlStorageConfig mySqlConfig,
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
        this.mySqlConfig = Objects.requireNonNull(mySqlConfig, "mySqlConfig");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.workspaceId = Objects.requireNonNull(workspaceScope, "workspaceScope").workspaceId();
        this.namedLockManager = new MySqlNamedLockManager(jdbcDataSource, mySqlConfig, this.workspaceId);
        try {
            new MySqlSchemaManager(jdbcDataSource, mySqlConfig).bootstrap();
            this.documentStore = new MySqlDocumentStore(jdbcDataSource, mySqlConfig, workspaceId);
            this.chunkStore = new MySqlChunkStore(jdbcDataSource, mySqlConfig, workspaceId);
            this.documentStatusStore = new MySqlDocumentStatusStore(jdbcDataSource, mySqlConfig, workspaceId);
            this.graphProjection = Objects.requireNonNull(graphProjection, "graphProjection");
            this.vectorProjection = Objects.requireNonNull(vectorProjection, "vectorProjection");
            this.graphStore = new LockedGraphStore();
        } catch (RuntimeException exception) {
            closeQuietly(graphProjection, exception);
            closeQuietly(vectorProjection, exception);
            closeOwnedResources();
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
        return vectorProjection;
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
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        lock.writeLock().lock();
        try {
            return namedLockManager.withExclusiveLock(() -> {
                var beforeSnapshot = captureSnapshot();
                try {
                    var baseGraphSnapshot = new Neo4jGraphSnapshot(beforeSnapshot.entities(), beforeSnapshot.relations());
                    var stagedGraphStore = new SnapshotBackedGraphStore(baseGraphSnapshot);
                    var stagedVectorStore = new SnapshotBackedHybridVectorStore(beforeSnapshot);
                    T result;
                    try (var connection = jdbcDataSource.getConnection()) {
                        result = withTransaction(connection, () -> operation.execute(new AtomicView(
                            new MySqlDocumentStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId),
                            new MySqlChunkStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId),
                            stagedGraphStore,
                            stagedVectorStore,
                            new MySqlDocumentStatusStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId)
                        )));
                    } catch (SQLException exception) {
                        throw new StorageException("Failed to open MySQL transaction", exception);
                    }

                    applyGraphChanges(baseGraphSnapshot, stagedGraphStore.snapshot());
                    applyMilvusSnapshot(beforeSnapshot, stagedVectorStore.namespaceRecords());
                    return result;
                } catch (RuntimeException failure) {
                    restoreSnapshots(beforeSnapshot, failure);
                    throw failure;
                }
            });
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var replacement = Objects.requireNonNull(snapshot, "snapshot");
        lock.writeLock().lock();
        try {
            namedLockManager.withExclusiveLock(() -> {
                var beforeSnapshot = captureSnapshot();
                try {
                    restoreMySqlSnapshot(replacement);
                    applyGraphSnapshot(new Neo4jGraphSnapshot(replacement.entities(), replacement.relations()));
                    restoreMilvusSnapshot(replacement);
                    return null;
                } catch (RuntimeException failure) {
                    restoreSnapshots(beforeSnapshot, failure);
                    throw failure;
                }
            });
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

    private SnapshotStore.Snapshot captureSnapshot() {
        var graphSnapshot = graphProjection.captureSnapshot();
        return new SnapshotStore.Snapshot(
            documentStore.list(),
            chunkStore.list(),
            graphSnapshot.entities(),
            graphSnapshot.relations(),
            Map.of(
                "chunks", vectorProjection.list("chunks"),
                "entities", vectorProjection.list("entities"),
                "relations", vectorProjection.list("relations")
            ),
            documentStatusStore.list()
        );
    }

    private void restoreSnapshots(SnapshotStore.Snapshot beforeSnapshot, RuntimeException failure) {
        try {
            restoreMySqlSnapshot(beforeSnapshot);
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
        try {
            applyGraphSnapshot(new Neo4jGraphSnapshot(beforeSnapshot.entities(), beforeSnapshot.relations()));
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
        try {
            restoreMilvusSnapshot(beforeSnapshot);
        } catch (RuntimeException exception) {
            addSuppressedIfDistinct(failure, exception);
        }
    }

    private void applyGraphSnapshot(Neo4jGraphSnapshot snapshot) {
        graphProjection.restore(snapshot);
    }

    private void applyGraphChanges(Neo4jGraphSnapshot beforeSnapshot, Neo4jGraphSnapshot afterSnapshot) {
        var before = Objects.requireNonNull(beforeSnapshot, "beforeSnapshot");
        var after = Objects.requireNonNull(afterSnapshot, "afterSnapshot");
        if (!canApplyGraphIncrementally(before, after)) {
            applyGraphSnapshot(after);
            return;
        }
        var beforeEntities = mapEntitiesById(before.entities());
        for (var entity : after.entities()) {
            if (!entity.equals(beforeEntities.get(entity.id()))) {
                graphProjection.saveEntity(entity);
            }
        }
        var beforeRelations = mapRelationsById(before.relations());
        for (var relation : after.relations()) {
            if (!relation.equals(beforeRelations.get(relation.id()))) {
                graphProjection.saveRelation(relation);
            }
        }
    }

    private void applyMilvusSnapshot(
        SnapshotStore.Snapshot baseSnapshot,
        Map<String, List<HybridVectorStore.EnrichedVectorRecord>> namespaceRecords
    ) {
        var changedNamespaces = new java.util.ArrayList<String>(VECTOR_NAMESPACES.size());
        for (var namespace : VECTOR_NAMESPACES) {
            var beforeVectors = baseSnapshot.vectors().getOrDefault(namespace, List.of());
            var records = namespaceRecords.getOrDefault(namespace, List.of());
            if (canApplyMilvusIncrementally(beforeVectors, records)) {
                if (!records.isEmpty()) {
                    vectorProjection.saveAllEnriched(namespace, records);
                    changedNamespaces.add(namespace);
                }
                continue;
            }
            vectorProjection.deleteNamespace(namespace);
            if (!records.isEmpty()) {
                vectorProjection.saveAllEnriched(namespace, records);
            }
            changedNamespaces.add(namespace);
        }
        if (!changedNamespaces.isEmpty()) {
            vectorProjection.flushNamespaces(changedNamespaces);
        }
    }

    private void restoreMilvusSnapshot(SnapshotStore.Snapshot snapshot) {
        var restored = buildEnrichedVectors(snapshot);
        replaceMilvusSnapshot(restored);
    }

    private Map<String, List<HybridVectorStore.EnrichedVectorRecord>> buildEnrichedVectors(SnapshotStore.Snapshot snapshot) {
        var vectors = snapshot.vectors();
        return Map.of(
            "chunks", HybridVectorPayloads.chunkPayloads(
                snapshot.chunks().stream().map(MySqlMilvusNeo4jStorageProvider::toChunk).toList(),
                vectors.getOrDefault("chunks", List.of())
            ),
            "entities", HybridVectorPayloads.entityPayloads(
                snapshot.entities().stream().map(MySqlMilvusNeo4jStorageProvider::toEntity).toList(),
                vectors.getOrDefault("entities", List.of())
            ),
            "relations", HybridVectorPayloads.relationPayloads(
                snapshot.relations().stream().map(MySqlMilvusNeo4jStorageProvider::toRelation).toList(),
                vectors.getOrDefault("relations", List.of())
            )
        );
    }

    private void replaceMilvusSnapshot(Map<String, List<HybridVectorStore.EnrichedVectorRecord>> namespaceRecords) {
        var changedNamespaces = new java.util.ArrayList<String>(VECTOR_NAMESPACES.size());
        for (var namespace : VECTOR_NAMESPACES) {
            vectorProjection.deleteNamespace(namespace);
            var records = namespaceRecords.getOrDefault(namespace, List.of());
            if (!records.isEmpty()) {
                vectorProjection.saveAllEnriched(namespace, records);
            }
            changedNamespaces.add(namespace);
        }
        vectorProjection.flushNamespaces(changedNamespaces);
    }

    private static boolean canApplyGraphIncrementally(Neo4jGraphSnapshot before, Neo4jGraphSnapshot after) {
        return after.entities().stream().map(GraphStore.EntityRecord::id).collect(java.util.stream.Collectors.toSet())
            .containsAll(before.entities().stream().map(GraphStore.EntityRecord::id).toList())
            && after.relations().stream().map(GraphStore.RelationRecord::id).collect(java.util.stream.Collectors.toSet())
            .containsAll(before.relations().stream().map(GraphStore.RelationRecord::id).toList());
    }

    private static boolean canApplyMilvusIncrementally(
        List<VectorStore.VectorRecord> beforeVectors,
        List<HybridVectorStore.EnrichedVectorRecord> afterRecords
    ) {
        if (beforeVectors.isEmpty()) {
            return true;
        }
        var afterIds = afterRecords.stream()
            .map(HybridVectorStore.EnrichedVectorRecord::id)
            .collect(java.util.stream.Collectors.toSet());
        return afterIds.containsAll(beforeVectors.stream().map(VectorStore.VectorRecord::id).toList());
    }

    private static Map<String, GraphStore.EntityRecord> mapEntitiesById(List<GraphStore.EntityRecord> entities) {
        var values = new LinkedHashMap<String, GraphStore.EntityRecord>(entities.size());
        for (var entity : entities) {
            values.put(entity.id(), entity);
        }
        return values;
    }

    private static Map<String, GraphStore.RelationRecord> mapRelationsById(List<GraphStore.RelationRecord> relations) {
        var values = new LinkedHashMap<String, GraphStore.RelationRecord>(relations.size());
        for (var relation : relations) {
            values.put(relation.id(), relation);
        }
        return values;
    }

    private void restoreMySqlSnapshot(SnapshotStore.Snapshot snapshot) {
        try (var connection = jdbcDataSource.getConnection()) {
            withTransaction(connection, () -> {
                deleteWorkspaceRows(connection, "document_status");
                deleteWorkspaceRows(connection, "chunks");
                deleteWorkspaceRows(connection, "documents");

                var documentStore = new MySqlDocumentStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId);
                var chunkStore = new MySqlChunkStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId);
                var statusStore = new MySqlDocumentStatusStore(MySqlJdbcConnectionAccess.forConnection(connection), mySqlConfig, workspaceId);
                for (var document : snapshot.documents()) {
                    documentStore.save(document);
                }
                for (var chunk : snapshot.chunks()) {
                    chunkStore.save(chunk);
                }
                for (var status : snapshot.documentStatuses()) {
                    statusStore.save(status);
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new StorageException("Failed to open MySQL transaction for restore", exception);
        }
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

    private void deleteWorkspaceRows(Connection connection, String baseTableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM " + mySqlConfig.qualifiedTableName(baseTableName) + " WHERE workspace_id = ?"
        )) {
            statement.setString(1, workspaceId);
            statement.executeUpdate();
        }
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

    private final class LockedGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            lock.writeLock().lock();
            try {
                graphProjection.saveEntity(entity);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            lock.writeLock().lock();
            try {
                graphProjection.saveRelation(relation);
            } finally {
                lock.writeLock().unlock();
            }
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            lock.readLock().lock();
            try {
                return graphProjection.loadEntity(entityId);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            lock.readLock().lock();
            try {
                return graphProjection.loadRelation(relationId);
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public List<EntityRecord> allEntities() {
            lock.readLock().lock();
            try {
                return graphProjection.allEntities();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public List<RelationRecord> allRelations() {
            lock.readLock().lock();
            try {
                return graphProjection.allRelations();
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            lock.readLock().lock();
            try {
                return graphProjection.findRelations(entityId);
            } finally {
                lock.readLock().unlock();
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

    private static final class SnapshotBackedGraphStore implements GraphStore {
        private final Map<String, EntityRecord> entitiesById;
        private final Map<String, RelationRecord> relationsById;

        private SnapshotBackedGraphStore(Neo4jGraphSnapshot snapshot) {
            this.entitiesById = new LinkedHashMap<>();
            for (var entity : snapshot.entities()) {
                entitiesById.put(entity.id(), entity);
            }
            this.relationsById = new LinkedHashMap<>();
            for (var relation : snapshot.relations()) {
                relationsById.put(relation.id(), relation);
            }
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            entitiesById.put(entity.id(), entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            relationsById.put(relation.id(), relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return java.util.Optional.ofNullable(entitiesById.get(entityId));
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return java.util.Optional.ofNullable(relationsById.get(relationId));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return entitiesById.values().stream()
                .sorted(java.util.Comparator.comparing(EntityRecord::id))
                .toList();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return relationsById.values().stream()
                .sorted(java.util.Comparator.comparing(RelationRecord::id))
                .toList();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return relationsById.values().stream()
                .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                .sorted(java.util.Comparator.comparing(RelationRecord::id))
                .toList();
        }

        private Neo4jGraphSnapshot snapshot() {
            return new Neo4jGraphSnapshot(allEntities(), allRelations());
        }
    }

    private static final class SnapshotBackedHybridVectorStore implements HybridVectorStore {
        private final Map<String, LinkedHashMap<String, EnrichedVectorRecord>> namespaceRecords;

        private SnapshotBackedHybridVectorStore(SnapshotStore.Snapshot snapshot) {
            this.namespaceRecords = new LinkedHashMap<>();
            seedNamespace("chunks", HybridVectorPayloads.chunkPayloads(
                snapshot.chunks().stream().map(MySqlMilvusNeo4jStorageProvider::toChunk).toList(),
                snapshot.vectors().getOrDefault("chunks", List.of())
            ));
            seedNamespace("entities", HybridVectorPayloads.entityPayloads(
                snapshot.entities().stream().map(MySqlMilvusNeo4jStorageProvider::toEntity).toList(),
                snapshot.vectors().getOrDefault("entities", List.of())
            ));
            seedNamespace("relations", HybridVectorPayloads.relationPayloads(
                snapshot.relations().stream().map(MySqlMilvusNeo4jStorageProvider::toRelation).toList(),
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
            var target = namespace(namespace);
            for (var record : records) {
                target.put(record.id(), record);
            }
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return inMemoryView().search(namespace, request);
        }

        private Map<String, List<EnrichedVectorRecord>> namespaceRecords() {
            var copied = new LinkedHashMap<String, List<EnrichedVectorRecord>>();
            for (var namespace : VECTOR_NAMESPACES) {
                copied.put(namespace, List.copyOf(namespace(namespace).values()));
            }
            return Map.copyOf(copied);
        }

        private void seedNamespace(String namespace, List<EnrichedVectorRecord> records) {
            var values = namespace(namespace);
            for (var record : records) {
                values.put(record.id(), record);
            }
        }

        private LinkedHashMap<String, EnrichedVectorRecord> namespace(String namespace) {
            return namespaceRecords.computeIfAbsent(Objects.requireNonNull(namespace, "namespace"), ignored -> new LinkedHashMap<>());
        }

        private io.github.lightrag.storage.memory.InMemoryVectorStore inMemoryView() {
            var store = new io.github.lightrag.storage.memory.InMemoryVectorStore();
            for (var entry : namespaceRecords.entrySet()) {
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
        public void saveEntity(EntityRecord entity) {
            delegate.saveEntity(entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            delegate.saveRelation(relation);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return delegate.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return delegate.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
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

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
