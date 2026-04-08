package io.github.lightrag.storage.postgres;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.indexing.HybridVectorPayloads;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.StorageAssembly;
import io.github.lightrag.storage.StorageCoordinator;
import io.github.lightrag.storage.SnapshotStore;
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

    private final SnapshotStore snapshotStore;
    private final StorageCoordinator coordinator;
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
        this.snapshotStore = components.snapshotStore;
        this.coordinator = (StorageCoordinator) StorageAssembly.builder()
            .relationalAdapter(components.relationalAdapter)
            .graphAdapter(components.graphAdapter)
            .vectorAdapter(components.vectorAdapter)
            .build()
            .toStorageProvider();
        this.graphStore = new MirroringGraphStore();
    }

    @Override
    public DocumentStore documentStore() {
        return coordinator.documentStore();
    }

    @Override
    public ChunkStore chunkStore() {
        return coordinator.chunkStore();
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return coordinator.vectorStore();
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return coordinator.documentStatusStore();
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        return coordinator.writeAtomically(operation);
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        coordinator.restore(snapshot);
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
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter);
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
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter);
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
        return new Components(snapshotStore, relationalAdapter, graphAdapter, vectorAdapter);
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
            Objects.requireNonNull(vectorAdapter, "vectorAdapter")
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
            return coordinator.graphStore().loadEntity(entityId);
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return coordinator.graphStore().loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return coordinator.graphStore().allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return coordinator.graphStore().allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return coordinator.graphStore().findRelations(entityId);
        }
    }

    private record Components(
        SnapshotStore snapshotStore,
        PostgresRelationalStorageAdapter relationalAdapter,
        GraphStorageAdapter graphAdapter,
        VectorStorageAdapter vectorAdapter
    ) {
        private Components {
            snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
            relationalAdapter = Objects.requireNonNull(relationalAdapter, "relationalAdapter");
            graphAdapter = Objects.requireNonNull(graphAdapter, "graphAdapter");
            vectorAdapter = Objects.requireNonNull(vectorAdapter, "vectorAdapter");
        }
    }
}
