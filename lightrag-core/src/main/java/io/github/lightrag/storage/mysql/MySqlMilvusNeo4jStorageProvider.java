package io.github.lightrag.storage.mysql;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.indexing.HybridVectorPayloads;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageCoordinator;
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

public final class MySqlMilvusNeo4jStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final WorkspaceScope DEFAULT_WORKSPACE = new WorkspaceScope("default");

    private final ReentrantReadWriteLock lock;
    private final MySqlNamedLockManager namedLockManager;
    private final ThreadLocal<Boolean> exclusiveNamedLockHeld;
    private final SnapshotStore snapshotStore;
    private final StorageCoordinator coordinator;
    private final DocumentStore lockedDocumentStore;
    private final ChunkStore lockedChunkStore;
    private final DocumentStatusStore lockedDocumentStatusStore;
    private final GraphStore lockedGraphStore;
    private final VectorStore lockedVectorStore;

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
        this(buildFromConfigs(mySqlConfig, milvusConfig, neo4jConfig, snapshotStore, workspaceScope));
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
        this(buildFromDataSourceConfigs(dataSource, mySqlConfig, milvusConfig, neo4jConfig, snapshotStore, workspaceScope));
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
        this(buildFromProjections(dataSource, mySqlConfig, snapshotStore, workspaceScope, graphProjection, vectorProjection, lock));
    }

    public MySqlMilvusNeo4jStorageProvider(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        this(buildFromAdapters(dataSource, mySqlConfig, snapshotStore, workspaceScope, graphAdapter, vectorAdapter));
    }

    private MySqlMilvusNeo4jStorageProvider(Components components) {
        this.lock = components.lock;
        this.snapshotStore = components.snapshotStore;
        this.coordinator = new StorageCoordinator(
            components.relationalAdapter,
            components.graphAdapter,
            components.vectorAdapter
        );
        this.namedLockManager = new MySqlNamedLockManager(
            components.relationalAdapter.dataSource(),
            components.relationalAdapter.config(),
            components.relationalAdapter.workspaceId()
        );
        this.exclusiveNamedLockHeld = ThreadLocal.withInitial(() -> Boolean.FALSE);
        this.lockedDocumentStore = new LockedDocumentStore(coordinator.documentStore());
        this.lockedChunkStore = new LockedChunkStore(coordinator.chunkStore());
        this.lockedDocumentStatusStore = new LockedDocumentStatusStore(coordinator.documentStatusStore());
        this.lockedGraphStore = new LockedGraphStore(coordinator.graphStore());
        this.lockedVectorStore = new LockedVectorStore(coordinator.vectorStore());
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
        return withExclusiveProviderLock(() -> namedLockManager.withExclusiveLock(() -> withExclusiveNamedLockScope(
            () -> coordinator.writeAtomically(operation)
        )));
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        withExclusiveProviderLock(() -> namedLockManager.withExclusiveLock(() -> withExclusiveNamedLockScope(() -> {
            coordinator.restore(source);
            return null;
        })));
    }

    @Override
    public void close() {
        coordinator.close();
    }

    private static Components buildFromConfigs(
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        var relationalAdapter = new MySqlRelationalStorageAdapter(
            Objects.requireNonNull(mySqlConfig, "mySqlConfig"),
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
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter, graphAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, new ReentrantReadWriteLock(true));
    }

    private static Components buildFromDataSourceConfigs(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        MilvusVectorConfig milvusConfig,
        Neo4jGraphConfig neo4jConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope
    ) {
        var relationalAdapter = new MySqlRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(mySqlConfig, "mySqlConfig"),
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
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter, graphAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, new ReentrantReadWriteLock(true));
    }

    private static Components buildFromProjections(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphProjection graphProjection,
        VectorProjection vectorProjection,
        ReentrantReadWriteLock lock
    ) {
        var relationalAdapter = new MySqlRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(mySqlConfig, "mySqlConfig"),
            Objects.requireNonNull(snapshotStore, "snapshotStore"),
            Objects.requireNonNull(workspaceScope, "workspaceScope")
        );
        var graphAdapter = new ProjectionGraphStorageAdapter(Objects.requireNonNull(graphProjection, "graphProjection"));
        var vectorAdapter = new MilvusVectorStorageAdapter(
            new ProjectionVectorProjectionAdapter(Objects.requireNonNull(vectorProjection, "vectorProjection")),
            snapshot -> buildMilvusPayloads(snapshot, relationalAdapter, graphAdapter)
        );
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter, Objects.requireNonNull(lock, "lock"));
    }

    private static Components buildFromAdapters(
        DataSource dataSource,
        MySqlStorageConfig mySqlConfig,
        SnapshotStore snapshotStore,
        WorkspaceScope workspaceScope,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        var relationalAdapter = new MySqlRelationalStorageAdapter(
            Objects.requireNonNull(dataSource, "dataSource"),
            Objects.requireNonNull(mySqlConfig, "mySqlConfig"),
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
        MySqlRelationalStorageAdapter relationalAdapter,
        GraphStorageAdapter graphAdapter
    ) {
        var relationalSnapshot = relationalAdapter.captureSnapshot();
        var graphSnapshot = graphAdapter.captureSnapshot();
        return Map.of(
            "chunks", HybridVectorPayloads.chunkPayloads(
                relationalSnapshot.chunks().stream().map(MySqlMilvusNeo4jStorageProvider::toChunk).toList(),
                snapshot.namespaces().getOrDefault("chunks", List.of())
            ),
            "entities", HybridVectorPayloads.entityPayloads(
                graphSnapshot.entities().stream().map(MySqlMilvusNeo4jStorageProvider::toEntity).toList(),
                snapshot.namespaces().getOrDefault("entities", List.of())
            ),
            "relations", HybridVectorPayloads.relationPayloads(
                graphSnapshot.relations().stream().map(MySqlMilvusNeo4jStorageProvider::toRelation).toList(),
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
            if (exclusiveNamedLockHeld.get()) {
                return supplier.get();
            }
            return namedLockManager.withSharedLock(supplier::get);
        } finally {
            readLock.unlock();
        }
    }

    private void withWriteLock(Runnable runnable) {
        withExclusiveProviderLock(() -> {
            namedLockManager.withExclusiveLock(() -> withExclusiveNamedLockScope(() -> {
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

    private <T> T withExclusiveNamedLockScope(RuntimeSupplier<T> supplier) {
        boolean previous = exclusiveNamedLockHeld.get();
        exclusiveNamedLockHeld.set(true);
        try {
            return supplier.get();
        } finally {
            if (previous) {
                exclusiveNamedLockHeld.set(true);
            } else {
                exclusiveNamedLockHeld.remove();
            }
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

    private final class LockedGraphStore implements GraphStore {
        private final GraphStore delegate;

        private LockedGraphStore(GraphStore delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
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

    private static final class ProjectionGraphStorageAdapter implements GraphStorageAdapter {
        private final GraphProjection projection;

        private ProjectionGraphStorageAdapter(GraphProjection projection) {
            this.projection = Objects.requireNonNull(projection, "projection");
        }

        @Override
        public GraphStore graphStore() {
            return projection;
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            var snapshot = projection.captureSnapshot();
            return new GraphSnapshot(snapshot.entities(), snapshot.relations());
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            writes.entities().forEach(projection::saveEntity);
            writes.relations().forEach(projection::saveRelation);
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            projection.restore(new Neo4jGraphSnapshot(snapshot.entities(), snapshot.relations()));
        }

        @Override
        public void close() {
            projection.close();
        }
    }

    private static final class ProjectionVectorProjectionAdapter implements MilvusVectorStorageAdapter.Projection {
        private final VectorProjection projection;

        private ProjectionVectorProjectionAdapter(VectorProjection projection) {
            this.projection = Objects.requireNonNull(projection, "projection");
        }

        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            projection.saveAll(namespace, vectors);
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return projection.search(namespace, queryVector, topK);
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return projection.list(namespace);
        }

        @Override
        public void saveAllEnriched(String namespace, List<EnrichedVectorRecord> records) {
            projection.saveAllEnriched(namespace, records);
        }

        @Override
        public List<VectorMatch> search(String namespace, SearchRequest request) {
            return projection.search(namespace, request);
        }

        @Override
        public void deleteNamespace(String namespace) {
            projection.deleteNamespace(namespace);
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            projection.flushNamespaces(namespaces);
        }

        @Override
        public void close() {
            projection.close();
        }
    }

    private record Components(
        SnapshotStore snapshotStore,
        MySqlRelationalStorageAdapter relationalAdapter,
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
