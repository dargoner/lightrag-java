package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresMilvusNeo4jStorageProviderTest {
    @Test
    void delegatesAtomicWriteToStorageCoordinatorAndPersistsPostgresGraphBaseline() {
        try (
            var container = newPostgresContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            PostgresStorageConfig config = startedConfig(container);
            RecordingGraphStorageAdapter graphAdapter = new RecordingGraphStorageAdapter();
            RecordingVectorStorageAdapter vectorAdapter = new RecordingVectorStorageAdapter();

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphAdapter,
                vectorAdapter
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test")));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test")));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Alice",
                        "person",
                        "Researcher",
                        List.of("A"),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                    return null;
                });

                assertThat(graphAdapter.applyCount()).isEqualTo(1);
                assertThat(vectorAdapter.applyCount()).isEqualTo(1);
                assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
                assertThat(countRows(dataSource, config, "entities", "entity-1")).isEqualTo(1);
            }
        }
    }


    @Test
    void commitsAcrossPostgresMilvusAndNeo4jStoresWithoutPgvector() {
        try (
            var container = newPostgresContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            PostgresStorageConfig config = startedConfig(container);
            RecordingGraphProjection graphProjection = new RecordingGraphProjection();
            RecordingMilvusProjection milvusProjection = new RecordingMilvusProjection();

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                milvusProjection,
                new ReentrantReadWriteLock(true)
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test")));
                    storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test")));
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        "doc-1",
                        DocumentStatus.PROCESSED,
                        "ok",
                        null
                    ));
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Alice",
                        "person",
                        "Researcher",
                        List.of("A"),
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                    return null;
                });

                assertThat(provider.documentStore().load("doc-1")).isPresent();
                assertThat(provider.chunkStore().load("doc-1:0")).isPresent();
                assertThat(provider.documentStatusStore().load("doc-1")).contains(
                    new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "ok", null)
                );
                assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
                assertThat(provider.vectorStore().list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    @Test
    void rollsBackPostgresRowsWhenGraphProjectionFailsAfterCommit() {
        try (
            var container = newPostgresContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            PostgresStorageConfig config = startedConfig(container);
            RecordingGraphProjection graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of("S"),
                List.of("doc-0:0")
            ));
            RecordingMilvusProjection milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));
            graphProjection.failOnSaveEntity(new IllegalStateException("projection failed"));

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
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
    void restoreRestoresPostgresRowsWhenMilvusFlushFails() {
        try (
            var container = newPostgresContainer();
            var dataSource = newDataSource(startedConfig(container))
        ) {
            PostgresStorageConfig config = startedConfig(container);
            RecordingGraphProjection graphProjection = new RecordingGraphProjection();
            graphProjection.saveEntity(new GraphStore.EntityRecord(
                "entity-0",
                "Seed",
                "seed",
                "Seed entity",
                List.of(),
                List.of("doc-0:0")
            ));
            RecordingMilvusProjection milvusProjection = new RecordingMilvusProjection();
            milvusProjection.saveAllEnriched("chunks", List.of(new HybridVectorStore.EnrichedVectorRecord(
                "doc-0:0",
                List.of(1.0d, 0.0d, 0.0d),
                "seed",
                List.of("seed")
            )));

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
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
                milvusProjection.failOnFlushNamespaces(new IllegalStateException("milvus flush failed"));

                assertThatThrownBy(() -> provider.restore(new SnapshotStore.Snapshot(
                    List.of(new DocumentStore.DocumentRecord("doc-1", "Replacement", "body", Map.of())),
                    List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of())),
                    List.of(new GraphStore.EntityRecord("entity-1", "Replacement", "person", "entity", List.of(), List.of("doc-1:0"))),
                    List.of(),
                    Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d)))),
                    List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "replacement", null))
                )))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("milvus flush failed");

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
                        List.of(),
                        List.of("doc-0:0")
                    ));
                assertThat(milvusProjection.list("chunks"))
                    .containsExactly(new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d)));
            }
        }
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }

    private static PostgresStorageConfig startedConfig(PostgreSQLContainer<?> container) {
        container.start();
        return new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "public",
            3,
            "rag_"
        );
    }

    private static HikariDataSource newDataSource(PostgresStorageConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private static int countRows(
        HikariDataSource dataSource,
        PostgresStorageConfig config,
        String tableName,
        String id
    ) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                 "SELECT COUNT(*) FROM " + config.qualifiedTableName(tableName) + " WHERE workspace_id = ? AND id = ?"
             )) {
            statement.setString(1, "default");
            statement.setString(2, id);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count rows for table " + tableName, exception);
        }
    }

    private static final class RecordingGraphProjection implements PostgresMilvusNeo4jStorageProvider.GraphProjection {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private RuntimeException failureOnSaveEntity;
        private RuntimeException failureOnRestore;

        @Override
        public void saveEntity(GraphStore.EntityRecord entity) {
            if (failureOnSaveEntity != null) {
                RuntimeException failure = failureOnSaveEntity;
                failureOnSaveEntity = null;
                throw failure;
            }
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

        void failOnSaveEntity(RuntimeException failure) {
            this.failureOnSaveEntity = Objects.requireNonNull(failure, "failure");
        }
    }

    private static final class RecordingMilvusProjection implements PostgresMilvusNeo4jStorageProvider.VectorProjection {
        private final Map<String, LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord>> namespaces = new LinkedHashMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
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
            LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord> target = namespace(namespace);
            for (HybridVectorStore.EnrichedVectorRecord record : records) {
                target.put(record.id(), record);
            }
        }

        @Override
        public List<VectorStore.VectorMatch> search(String namespace, HybridVectorStore.SearchRequest request) {
            throw new UnsupportedOperationException("Not needed in this test");
        }

        @Override
        public void deleteNamespace(String namespace) {
            namespace(namespace).clear();
        }

        @Override
        public void flushNamespaces(List<String> namespaces) {
            if (failureOnFlushNamespaces != null) {
                RuntimeException failure = failureOnFlushNamespaces;
                failureOnFlushNamespaces = null;
                throw failure;
            }
        }

        @Override
        public void close() {
            closed.set(true);
        }

        void failOnFlushNamespaces(RuntimeException failure) {
            this.failureOnFlushNamespaces = Objects.requireNonNull(failure, "failure");
        }

        private LinkedHashMap<String, HybridVectorStore.EnrichedVectorRecord> namespace(String namespace) {
            return namespaces.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>());
        }
    }

    private static final class RecordingGraphStorageAdapter implements GraphStorageAdapter {
        private final RecordingGraphProjection projection = new RecordingGraphProjection();
        private int applyCount;

        @Override
        public GraphStore graphStore() {
            return projection;
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            return new GraphSnapshot(projection.allEntities(), projection.allRelations());
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            applyCount++;
            for (var entity : writes.entities()) {
                projection.saveEntity(entity);
            }
            for (var relation : writes.relations()) {
                projection.saveRelation(relation);
            }
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            projection.restore(new Neo4jGraphSnapshot(snapshot.entities(), snapshot.relations()));
        }

        int applyCount() {
            return applyCount;
        }
    }

    private static final class RecordingVectorStorageAdapter implements VectorStorageAdapter {
        private final RecordingMilvusProjection projection = new RecordingMilvusProjection();
        private int applyCount;

        @Override
        public VectorStore vectorStore() {
            return projection;
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            return new VectorSnapshot(Map.of(
                "chunks", projection.list("chunks"),
                "entities", projection.list("entities"),
                "relations", projection.list("relations")
            ));
        }

        @Override
        public void apply(StagedVectorWrites writes) {
            applyCount++;
            for (var entry : writes.upserts().entrySet()) {
                projection.saveAll(entry.getKey(), entry.getValue().stream().map(VectorWrite::toVectorRecord).toList());
            }
            projection.flushNamespaces(List.copyOf(writes.upserts().keySet()));
        }

        @Override
        public void restore(VectorSnapshot snapshot) {
            for (var namespace : List.of("chunks", "entities", "relations")) {
                projection.deleteNamespace(namespace);
                var vectors = snapshot.namespaces().getOrDefault(namespace, List.of());
                if (!vectors.isEmpty()) {
                    projection.saveAll(namespace, vectors);
                }
            }
            projection.flushNamespaces(List.of("chunks", "entities", "relations"));
        }

        int applyCount() {
            return applyCount;
        }
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        private final Map<Path, Snapshot> snapshots = new LinkedHashMap<>();

        @Override
        public void save(Path path, Snapshot snapshot) {
            snapshots.put(path, snapshot);
        }

        @Override
        public Snapshot load(Path path) {
            return snapshots.get(path);
        }

        @Override
        public List<Path> list() {
            return snapshots.keySet().stream().toList();
        }
    }
}
