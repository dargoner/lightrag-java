package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.indexing.HybridVectorPayloads;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.StorageCoordinator;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.milvus.MilvusVectorConfig;
import io.github.lightrag.storage.milvus.MilvusVectorStorageAdapter;
import io.github.lightrag.storage.milvus.MilvusVectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphConfig;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import io.github.lightrag.storage.neo4j.Neo4jGraphStorageAdapter;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PostgresMilvusNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");

    private final ReentrantReadWriteLock lock;
    private final PostgresAdvisoryLockManager advisoryLockManager;
    private final ThreadLocal<Boolean> exclusiveAdvisoryLockHeld;
    private final SnapshotStore snapshotStore;
    private final StorageCoordinator coordinator;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final TaskStore lockedTaskStore;
    private final TaskStageStore lockedTaskStageStore;
    private final TaskDocumentStore lockedTaskDocumentStore;
    private final VectorStore lockedVectorStore;
    private final GraphStore graphStore;
    private final DocumentGraphSnapshotStore documentGraphSnapshotStore;
    private final DocumentGraphJournalStore documentGraphJournalStore;

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
        this(buildFromConfigs(postgresConfig, milvusConfig, neo4jConfig, snapshotStore, workspaceScope));
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        this(buildFromDataSourceConfigs(dataSource, postgresConfig, milvusConfig, neo4jConfig, snapshotStore, workspaceScope));
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
        this(buildFromProjections(dataSource, postgresConfig, snapshotStore, workspaceScope, graphProjection, vectorProjection, lock));
    }

    public PostgresMilvusNeo4jStorageProvider(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        this(buildFromAdapters(dataSource, postgresConfig, snapshotStore, workspaceScope, graphAdapter, vectorAdapter));
    }

    private PostgresMilvusNeo4jStorageProvider(Components components) {
        this.lock = components.lock;
        this.snapshotStore = components.snapshotStore;
        this.coordinator = new StorageCoordinator(
            components.relationalAdapter,
            components.graphAdapter,
            components.vectorAdapter
        );
        this.advisoryLockManager = new PostgresAdvisoryLockManager(
            components.relationalAdapter.dataSource(),
            components.relationalAdapter.config(),
            components.relationalAdapter.workspaceId()
        );
        this.exclusiveAdvisoryLockHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);
        this.lockedDocumentStore = new LockedDocumentStore(coordinator.documentStore());
        this.lockedChunkStore = new LockedChunkStore(coordinator.chunkStore());
        this.lockedDocumentStatusStore = new LockedDocumentStatusStore(coordinator.documentStatusStore());
        this.lockedTaskStore = new LockedTaskStore(coordinator.taskStore());
        this.lockedTaskStageStore = new LockedTaskStageStore(coordinator.taskStageStore());
        this.lockedTaskDocumentStore = new LockedTaskDocumentStore(coordinator.taskDocumentStore());
        this.lockedVectorStore = new LockedVectorStore(coordinator.vectorStore());
        this.graphStore = new MirroringGraphStore();
        this.documentGraphSnapshotStore = coordinator.documentGraphSnapshotStore();
        this.documentGraphJournalStore = coordinator.documentGraphJournalStore();
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
        return withExclusiveProviderLock(() -> advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(
            () -> coordinator.writeAtomically(operation)
        )));
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        withExclusiveProviderLock(() -> advisoryLockManager.withExclusiveLock(() -> withExclusiveAdvisoryScope(() -> {
            coordinator.restore(source);
            return null;
        })));
    }

    @Override
    public void close() {
        coordinator.close();
    }

    private static Components buildFromConfigs(
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        var relationalAdapter = new PostgresRelationalStorageAdapter(
            Objects.requireNonNull(postgresConfig, "postgresConfig"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        );
        var graphAdapter = new Neo4jGraphStorageAdapter(
            Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
            workspaceScope
        );
        var vectorAdapter = new MilvusVectorStorageAdapter(
            new MilvusVectorStore(
                Objects.requireNonNull(milvusConfig, "milvusConfig"),
                workspaceScope.workspaceId()
            ),
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, new ReentrantReadWriteLock(true));
    }

    private static Components buildFromDataSourceConfigs(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        var relationalAdapter = new PostgresRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(postgresConfig, "postgresConfig"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        );
        var graphAdapter = new Neo4jGraphStorageAdapter(
            Objects.requireNonNull(neo4jConfig, "neo4jConfig"),
            workspaceScope
        );
        var vectorAdapter = new MilvusVectorStorageAdapter(
            new MilvusVectorStore(
                Objects.requireNonNull(milvusConfig, "milvusConfig"),
                workspaceScope.workspaceId()
            ),
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, new ReentrantReadWriteLock(true));
    }

    private static Components buildFromProjections(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection,
        ReentrantReadWriteLock lock
    ) {
        Objects.requireNonNull(lock, "lock");
        var relationalAdapter = new PostgresRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(postgresConfig, "postgresConfig"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        );
        var graphAdapter = new Neo4jGraphStorageAdapter(Objects.requireNonNull(graphProjection, "graphProjection"));
        var vectorAdapter = new MilvusVectorStorageAdapter(
            Objects.requireNonNull(vectorProjection, "vectorProjection"),
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, lock);
    }

    private static Components buildFromAdapters(
        DataSource dataSource,
        PostgresStorageConfig postgresConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        var relationalAdapter = new PostgresRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(postgresConfig, "postgresConfig"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        );
        return new Components(
            snapshotStore,
            relationalAdapter,
            Objects.requireNonNull(graphAdapter, "graphAdapter"),
            Objects.requireNonNull(vectorAdapter, "vectorAdapter"),
            new ReentrantReadWriteLock(true)
        );
    }

    private static Map<String, List<io.github.lightrag.storage.HybridVectorStore.EnrichedVectorRecord>> buildMilvusPayloads(
        VectorStorageAdapter.VectorSnapshot snapshot,
        PostgresRelationalStorageAdapter relationalAdapter
    ) {
        return Map.of(
            "chunks", HybridVectorPayloads.chunkPayloads(
                relationalAdapter.chunkStore().list().stream().map(PostgresMilvusNeo4jStorageProvider::toChunk).toList(),
                snapshot.namespaces().getOrDefault("chunks", List.of())
            ),
            "entities", HybridVectorPayloads.entityPayloads(
                relationalAdapter.graphStore().allEntities().stream().map(PostgresMilvusNeo4jStorageProvider::toEntity).toList(),
                snapshot.namespaces().getOrDefault("entities", List.of())
            ),
            "relations", HybridVectorPayloads.relationPayloads(
                relationalAdapter.graphStore().allRelations().stream().map(PostgresMilvusNeo4jStorageProvider::toRelation).toList(),
                snapshot.namespaces().getOrDefault("relations", List.of())
            )
        );
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
            relationRecord.srcId(),
            relationRecord.tgtId(),
            relationRecord.keywords(),
            relationRecord.description(),
            relationRecord.weight(),
            relationRecord.sourceId(),
            relationRecord.filePath()
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

    public interface GraphProjection extends Neo4jGraphStorageAdapter.Projection {
        @Override
        Neo4jGraphSnapshot captureSnapshot();

        @Override
        void restore(Neo4jGraphSnapshot snapshot);

        @Override
        void close();
    }

    public interface VectorProjection extends MilvusVectorStorageAdapter.Projection {
        @Override
        void deleteNamespace(String namespace);

        @Override
        void flushNamespaces(List<String> namespaces);

        @Override
        void close();
    }

    private final class MirroringGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            writeAtomically(storage -> {
                storage.graphStore().saveEntity(entity);
                return null;
            });
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            writeAtomically(storage -> {
                storage.graphStore().saveRelation(relation);
                return null;
            });
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return withReadLock(() -> coordinator.graphStore().loadEntity(entityId));
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return withReadLock(() -> coordinator.graphStore().loadRelation(relationId));
        }

        @Override
        public List<EntityRecord> loadEntities(List<String> entityIds) {
            return withReadLock(() -> coordinator.graphStore().loadEntities(entityIds));
        }

        @Override
        public List<RelationRecord> loadRelations(List<String> relationIds) {
            return withReadLock(() -> coordinator.graphStore().loadRelations(relationIds));
        }

        @Override
        public List<EntityRecord> allEntities() {
            return withReadLock(() -> coordinator.graphStore().allEntities());
        }

        @Override
        public List<RelationRecord> allRelations() {
            return withReadLock(() -> coordinator.graphStore().allRelations());
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return withReadLock(() -> coordinator.graphStore().findRelations(entityId));
        }

        @Override
        public Map<String, List<RelationRecord>> findRelations(List<String> entityIds) {
            return withReadLock(() -> coordinator.graphStore().findRelations(entityIds));
        }
    }

    private final class LockedDocumentStore implements DocumentStore {
        private final DocumentStore delegate;

        private LockedDocumentStore(DocumentStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
        public Map<String, ChunkRecord> loadAll(List<String> chunkIds) {
            return withReadLock(() -> delegate.loadAll(chunkIds));
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
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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

    private final class LockedVectorStore implements HybridVectorStore {
        private final VectorStore delegate;

        private LockedVectorStore(VectorStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
            withWriteLock(() -> hybridDelegate().saveAllEnriched(namespace, records));
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return withReadLock(() -> hybridDelegate().search(namespace, request));
        }

        private HybridVectorStore hybridDelegate() {
            if (delegate instanceof HybridVectorStore hybridVectorStore) {
                return hybridVectorStore;
            }
            throw new UnsupportedOperationException("Delegate vector store does not support hybrid search");
        }
    }

    private <T> T withReadLock(RuntimeSupplier<T> supplier) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return supplier.get();
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

    private record Components(
        SnapshotStore snapshotStore,
        PostgresRelationalStorageAdapter relationalAdapter,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter,
        ReentrantReadWriteLock lock
    ) {
        private Components {
            snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
            relationalAdapter = Objects.requireNonNull(relationalAdapter, "relationalAdapter");
            graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
            vectorAdapter = Objects.requireNonNull(vectorAdapter, "vectorAdapter");
            lock = Objects.requireNonNull(lock, "lock");
        }
    }

    @FunctionalInterface
    private interface RuntimeSupplier<T> {
        T get();
    }
}
