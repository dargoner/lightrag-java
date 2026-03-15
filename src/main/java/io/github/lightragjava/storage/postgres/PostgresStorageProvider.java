package io.github.lightragjava.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class PostgresStorageProvider implements AtomicStorageProvider, AutoCloseable {
    private static final UnsupportedDocumentStore DOCUMENT_STORE = new UnsupportedDocumentStore();
    private static final UnsupportedChunkStore CHUNK_STORE = new UnsupportedChunkStore();
    private static final UnsupportedGraphStore GRAPH_STORE = new UnsupportedGraphStore();
    private static final UnsupportedVectorStore VECTOR_STORE = new UnsupportedVectorStore();
    private static final AtomicStorageView ATOMIC_VIEW = new AtomicView(
        DOCUMENT_STORE,
        CHUNK_STORE,
        GRAPH_STORE,
        VECTOR_STORE
    );

    private final HikariDataSource dataSource;
    private final SnapshotStore snapshotStore;

    public PostgresStorageProvider(PostgresStorageConfig config, SnapshotStore snapshotStore) {
        Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.dataSource = createDataSource(config);
        try {
            new PostgresSchemaManager(dataSource, config).bootstrap();
        } catch (RuntimeException exception) {
            dataSource.close();
            throw exception;
        }
    }

    @Override
    public DocumentStore documentStore() {
        return DOCUMENT_STORE;
    }

    @Override
    public ChunkStore chunkStore() {
        return CHUNK_STORE;
    }

    @Override
    public GraphStore graphStore() {
        return GRAPH_STORE;
    }

    @Override
    public VectorStore vectorStore() {
        return VECTOR_STORE;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        throw new UnsupportedOperationException("Atomic writes are not implemented yet");
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        throw new UnsupportedOperationException("Restore is not implemented yet");
    }

    @Override
    public void close() {
        dataSource.close();
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

    private static final class UnsupportedDocumentStore implements DocumentStore {
        @Override
        public void save(DocumentRecord document) {
            throw unsupported();
        }

        @Override
        public Optional<DocumentRecord> load(String documentId) {
            throw unsupported();
        }

        @Override
        public List<DocumentRecord> list() {
            throw unsupported();
        }

        @Override
        public boolean contains(String documentId) {
            throw unsupported();
        }
    }

    private static final class UnsupportedChunkStore implements ChunkStore {
        @Override
        public void save(ChunkRecord chunk) {
            throw unsupported();
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            throw unsupported();
        }

        @Override
        public List<ChunkRecord> list() {
            throw unsupported();
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            throw unsupported();
        }
    }

    private static final class UnsupportedGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
            throw unsupported();
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            throw unsupported();
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            throw unsupported();
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            throw unsupported();
        }

        @Override
        public List<EntityRecord> allEntities() {
            throw unsupported();
        }

        @Override
        public List<RelationRecord> allRelations() {
            throw unsupported();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            throw unsupported();
        }
    }

    private static final class UnsupportedVectorStore implements VectorStore {
        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
            throw unsupported();
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            throw unsupported();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            throw unsupported();
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("PostgreSQL stores are not implemented yet");
    }
}
