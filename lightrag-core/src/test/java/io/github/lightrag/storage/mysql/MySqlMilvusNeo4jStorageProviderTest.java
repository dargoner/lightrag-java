package io.github.lightrag.storage.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlMilvusNeo4jStorageProviderTest {
    @Test
    void rollsBackMySqlRowsWhenProjectionFailsAfterCommit() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            graphProjection.saveRelation(new GraphStore.RelationRecord(
                "relation-0",
                "entity-0",
                "entity-0",
                "self",
                "Seed relation",
                1.0d,
                List.of("doc-0:0")
            ));

            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));
            graphProjection.failOnRestore(new IllegalStateException("projection failed"));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "incoming",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
                    return null;
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("projection failed");

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(graphProjection.allEntities())
                    .containsExactly(new GraphStore.EntityRecord(
                        "entity-0",
                        "Seed",
                        "seed",
                        "Seed entity",
                        List.of("S"),
                        List.of("doc-0:0")
                    ));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    @Test
    void restoreRestoresMySqlRowsWhenGraphRestoreFails() {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of(),
                List.of("doc-0:0")
            ));
            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                graphProjection.failOnRestore(new IllegalStateException("graph restore failed"));

                assertThatThrownBy(() -> provider.restore(new SnapshotStore.Snapshot(
                    List.of(new DocumentStore.DocumentRecord("doc-1", "Replacement", "body", Map.of())),
                    List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of())),
                    List.of(new GraphStore.EntityRecord("entity-1", "Replacement", "person", "entity", List.of(), List.of("doc-1:0"))),
                    List.of(),
                    Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d)))),
                    List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "replacement", null))
                )))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("graph restore failed");

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    @Test
    void rollsBackStateWhenMilvusDeleteFailsAfterCommit() {
        assertRollbackWhenMilvusProjectionFails(
            milvusProjection -> milvusProjection.failOnDeleteNamespace(new IllegalStateException("milvus delete failed")),
            "milvus delete failed"
        );
    }

    @Test
    void rollsBackStateWhenMilvusSaveFailsAfterCommit() {
        assertRollbackWhenMilvusProjectionFails(
            milvusProjection -> milvusProjection.failOnSaveAllEnriched(new IllegalStateException("milvus save failed")),
            "milvus save failed"
        );
    }

    @Test
    void rollsBackStateWhenMilvusFlushFailsAfterCommit() {
        assertRollbackWhenMilvusProjectionFails(
            milvusProjection -> milvusProjection.failOnFlushNamespaces(new IllegalStateException("milvus flush failed")),
            "milvus flush failed"
        );
    }

    private static void assertRollbackWhenMilvusProjectionFails(
        java.util.function.Consumer<RecordingMilvusProjection> failer,
        String expectedMessage
    ) {
        try (
            var container = newMySqlContainer();
            var dataSource = newDataSource(startedConfig(container));
        ) {
            var config = startedConfig(container);
            new MySqlSchemaManager(dataSource, config).bootstrap();

            var graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            var milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));

            try (var provider = new MySqlMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    "doc-0",
                    DocumentStatus.PROCESSED,
                    "seeded",
                    null
                ));

                failer.accept(milvusProjection);

                assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "incoming",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Incoming",
                        "person",
                        "Incoming entity",
                        List.of(),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d))));
                    return null;
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(expectedMessage);

                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                assertThat(provider.documentStatusStore().list())
                    .containsExactly(new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null));
                assertThat(graphProjection.allEntities())
                    .containsExactly(new GraphStore.EntityRecord(
                        "entity-0",
                        "Seed",
                        "seed",
                        "Seed entity",
                        List.of("S"),
                        List.of("doc-0:0")
                    ));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    private static MySQLContainer<?> newMySqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    private static MySqlStorageConfig startedConfig(MySQLContainer<?> container) {
        container.start();
        return new MySqlStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "rag_"
        );
    }

    private static HikariDataSource newDataSource(MySqlStorageConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static final class RecordingGraphProjection implements MySqlMilvusNeo4jStorageProvider.GraphProjection {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private RuntimeException failureOnRestore;

        @Override
        public void saveEntity(GraphStore.EntityRecord entity) {
            entities.put(entity.id(), entity);
        }

        @Override
        public void saveRelation(GraphStore.RelationRecord relation) {
            relations.put(relation.id(), relation);
        }

        @Override
        public Optional<GraphStore.EntityRecord> loadEntity(String entityId) {
            return Optional.ofNullable(entities.get(entityId));
        }

        @Override
        public Optional<GraphStore.RelationRecord> loadRelation(String relationId) {
            return Optional.ofNullable(relations.get(relationId));
        }

        @Override
        public List<GraphStore.EntityRecord> allEntities() {
            return entities.values().stream().toList();
        }

        @Override
        public List<GraphStore.RelationRecord> allRelations() {
            return relations.values().stream().toList();
        }

        @Override
        public List<GraphStore.RelationRecord> findRelations(String entityId) {
            return relations.values().stream()
                .filter(relation -> relation.sourceEntityId().equals(entityId) || relation.targetEntityId().equals(entityId))
                .toList();
        }

        @Override
        public Neo4jGraphSnapshot captureSnapshot() {
            return new Neo4jGraphSnapshot(allEntities(), allRelations());
        }

        @Override
        public void restore(Neo4jGraphSnapshot snapshot) {
            if (failureOnRestore != null) {
                throw failureOnRestore;
            }
            entities.clear();
            relations.clear();
            snapshot.entities().forEach(entity -> entities.put(entity.id(), entity));
            snapshot.relations().forEach(relation -> relations.put(relation.id(), relation));
        }

        @Override
        public void close() {
        }

        void failOnRestore(RuntimeException failure) {
            this.failureOnRestore = Objects.requireNonNull(failure, "failure");
        }
    }

    private static final class RecordingMilvusProjection implements MySqlMilvusNeo4jStorageProvider.VectorProjection {
        private final Map<String, LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord>> namespaces = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private RuntimeException failureOnDeleteNamespace;
        private RuntimeException failureOnSaveAllEnriched;
        private RuntimeException failureOnFlushNamespaces;

        @Override
        public void saveAll(String namespace, List<VectorStore.VectorRecord> vectors) {
            saveAllEnriched(namespace, vectors.stream()
                .map(vector -> new HybridVectorStore.EnrichedVectorRecord(vector.id(), vector.vector(), "", List.of()))
                .toList());
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public List<VectorStore.VectorRecord> list(String namespace) {
            return namespace(namespace).values().stream()
                .map(HybridVectorStore.EnrichedVectorRecord::toVectorRecord)
                .sorted(java.util.Comparator.comparing(VectorStore.VectorRecord::id))
                .toList();
        }

        @Override
        public void saveAllEnriched(String namespace, List<HybridVectorStore.EnrichedVectorRecord> records) {
            if (failureOnSaveAllEnriched != null) {
                var failure = failureOnSaveAllEnriched;
                failureOnSaveAllEnriched = null;
                throw failure;
            }
            var target = namespace(namespace);
            for (var record : records) {
                target.put(record.id(), record);
            }
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, HybridVectorStore.SearchRequest request) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public void deleteNamespace(String namespace) {
            if (failureOnDeleteNamespace != null) {
                var failure = failureOnDeleteNamespace;
                failureOnDeleteNamespace = null;
                throw failure;
            }
            namespace(namespace).clear();
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            if (failureOnFlushNamespaces != null) {
                var failure = failureOnFlushNamespaces;
                failureOnFlushNamespaces = null;
                throw failure;
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord> namespace(String namespace) {
            return namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
        }

        void failOnDeleteNamespace(RuntimeException failure) {
            this.failureOnDeleteNamespace = Objects.requireNonNull(failure, "failure");
        }

        void failOnSaveAllEnriched(RuntimeException failure) {
            this.failureOnSaveAllEnriched = Objects.requireNonNull(failure, "failure");
        }

        void failOnFlushNamespaces(RuntimeException failure) {
            this.failureOnFlushNamespaces = Objects.requireNonNull(failure, "failure");
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            throw new UnsupportedOperationException("Not used in this test");
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
