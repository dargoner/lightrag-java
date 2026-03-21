package io.github.lightragjava.storage.neo4j;

import io.github.lightragjava.persistence.FileSnapshotStore;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresNeo4jStorageProviderTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @Container
    private static final Neo4jContainer<?> NEO4J = new Neo4jContainer<>("neo4j:5-community")
        .withAdminPassword("password");

    @BeforeEach
    void resetSharedNeo4j() {
        try (var graphStore = newGraphStore()) {
            graphStore.restore(new Neo4jGraphSnapshot(List.of(), List.of()));
        }
    }

    @Test
    void exposesStableTopLevelStoresAndDistinctAtomicViewStores() {
        try (var provider = newProvider(new FileSnapshotStore())) {
            assertThat(provider.documentStore()).isSameAs(provider.documentStore());
            assertThat(provider.chunkStore()).isSameAs(provider.chunkStore());
            assertThat(provider.graphStore()).isSameAs(provider.graphStore());
            assertThat(provider.vectorStore()).isSameAs(provider.vectorStore());
            assertThat(provider.snapshotStore()).isSameAs(provider.snapshotStore());

            var atomicDocumentStore = new AtomicReference<DocumentStore>();
            var atomicChunkStore = new AtomicReference<ChunkStore>();
            var atomicGraphStore = new AtomicReference<GraphStore>();
            var atomicVectorStore = new AtomicReference<VectorStore>();

            provider.writeAtomically(storage -> {
                atomicDocumentStore.set(storage.documentStore());
                atomicChunkStore.set(storage.chunkStore());
                atomicGraphStore.set(storage.graphStore());
                atomicVectorStore.set(storage.vectorStore());
                return null;
            });

            assertThat(atomicDocumentStore.get()).isNotSameAs(provider.documentStore());
            assertThat(atomicChunkStore.get()).isNotSameAs(provider.chunkStore());
            assertThat(atomicGraphStore.get()).isNotSameAs(provider.graphStore());
            assertThat(atomicVectorStore.get()).isNotSameAs(provider.vectorStore());
        }
    }

    @Test
    void topLevelGraphWritesMirrorIntoAtomicGraphView() {
        try (var provider = newProvider(new FileSnapshotStore())) {
            var entity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A"),
                List.of("chunk-1")
            );
            var relation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("chunk-1")
            );

            provider.graphStore().saveEntity(entity);
            provider.graphStore().saveRelation(relation);

            provider.writeAtomically(storage -> {
                assertThat(storage.graphStore().loadEntity("entity-1")).contains(entity);
                assertThat(storage.graphStore().loadRelation("relation-1")).contains(relation);
                return null;
            });
        }
    }

    @Test
    void commitsAcrossPostgresAndNeo4jStores() {
        try (var provider = newProvider(new FileSnapshotStore())) {
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
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-2",
                    "knows",
                    "Alice knows Bob",
                    0.9d,
                    List.of("doc-1:0")
                ));
                storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                return null;
            });

            assertThat(provider.documentStore().load("doc-1")).isPresent();
            assertThat(provider.chunkStore().load("doc-1:0")).isPresent();
            assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
            assertThat(provider.graphStore().loadRelation("relation-1")).isPresent();
            assertThat(provider.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));
        }
    }

    @Test
    void restoreReplacesCurrentProviderState() {
        var replacement = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Snapshot", "Body", Map.of("source", "snapshot"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "snapshot"))),
            List.of(new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A"),
                List.of("doc-1:0")
            )),
            List.of(new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "knows",
                "Alice knows Bob",
                0.9d,
                List.of("doc-1:0")
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))))
        );

        try (var provider = newProvider(new FileSnapshotStore())) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old", "Old", Map.of()));
            provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-old:0", "doc-old", "Old", 3, 0, Map.of()));
            provider.graphStore().saveEntity(new GraphStore.EntityRecord("entity-old", "Old", "type", "Old", List.of(), List.of()));
            provider.graphStore().saveRelation(new GraphStore.RelationRecord("relation-old", "entity-old", "entity-old", "self", "Old", 0.1d, List.of()));
            provider.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-old:0", List.of(0.0d, 1.0d, 0.0d))));

            provider.restore(replacement);

            assertThat(provider.documentStore().list()).containsExactlyElementsOf(replacement.documents());
            assertThat(provider.chunkStore().list()).containsExactlyElementsOf(replacement.chunks());
            assertThat(provider.graphStore().allEntities()).containsExactlyElementsOf(replacement.entities());
            assertThat(provider.graphStore().allRelations()).containsExactlyElementsOf(replacement.relations());
            assertThat(provider.vectorStore().list("chunks")).containsExactlyElementsOf(replacement.vectors().get("chunks"));
            assertThat(provider.vectorStore().list("entities")).isEmpty();
        }
    }

    @Test
    void rollsBackAllStoresWhenProjectionFailsAfterPostgresCommit() {
        var tablePrefix = nextPrefix();
        var originalDocument = new DocumentStore.DocumentRecord("doc-0", "Existing", "seed", Map.of("seed", "true"));
        var originalChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        var originalEntity = new GraphStore.EntityRecord(
            "entity-0",
            "Seed",
            "seed",
            "Seed entity",
            List.of("S"),
            List.of("doc-0:0")
        );
        var originalRelation = new GraphStore.RelationRecord(
            "relation-0",
            "entity-0",
            "entity-0",
            "self",
            "Seed relation",
            1.0d,
            List.of("doc-0:0")
        );
        var originalVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d, 0.0d));

        try (var provider = newProvider(new FileSnapshotStore(), tablePrefix)) {
            provider.documentStore().save(originalDocument);
            provider.chunkStore().save(originalChunk);
            provider.graphStore().saveEntity(originalEntity);
            provider.graphStore().saveRelation(originalRelation);
            provider.vectorStore().saveAll("chunks", List.of(originalVector));
        }

        try (var provider = newFailingProvider(new FileSnapshotStore(), tablePrefix)) {
            assertThatThrownBy(() -> provider.writeAtomically(storage -> {
                storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
                storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Incoming",
                    "seed",
                    "Incoming entity",
                    List.of(),
                    List.of("doc-1:0")
                ));
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-0",
                    "links_to",
                    "Incoming relation",
                    0.5d,
                    List.of("doc-1:0")
                ));
                storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d, 0.0d))));
                return null;
            }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");

            assertThat(provider.documentStore().list()).containsExactly(originalDocument);
            assertThat(provider.chunkStore().list()).containsExactly(originalChunk);
            assertThat(provider.graphStore().allEntities()).containsExactly(originalEntity);
            assertThat(provider.graphStore().allRelations()).containsExactly(originalRelation);
            assertThat(provider.vectorStore().list("chunks")).containsExactly(originalVector);
        }
    }

    private static Neo4jGraphStore newGraphStore() {
        return new Neo4jGraphStore(newNeo4jConfig());
    }

    private static PostgresNeo4jStorageProvider newProvider(SnapshotStore snapshotStore) {
        return newProvider(snapshotStore, nextPrefix());
    }

    private static PostgresNeo4jStorageProvider newProvider(SnapshotStore snapshotStore, String tablePrefix) {
        return new PostgresNeo4jStorageProvider(
            newPostgresConfig(tablePrefix),
            newNeo4jConfig(),
            snapshotStore
        );
    }

    private static PostgresNeo4jStorageProvider newFailingProvider(SnapshotStore snapshotStore) {
        return newFailingProvider(snapshotStore, nextPrefix());
    }

    private static PostgresNeo4jStorageProvider newFailingProvider(SnapshotStore snapshotStore, String tablePrefix) {
        return new PostgresNeo4jStorageProvider(
            new PostgresStorageProvider(
                newPostgresConfig(tablePrefix),
                snapshotStore
            ),
            newGraphStore(),
            (entities, relations) -> {
                throw new IllegalStateException("projection failed");
            }
        );
    }

    private static PostgresStorageConfig newPostgresConfig(String tablePrefix) {
        return new PostgresStorageConfig(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            "lightrag",
            3,
            tablePrefix
        );
    }

    private static Neo4jGraphConfig newNeo4jConfig() {
        return new Neo4jGraphConfig(
            NEO4J.getBoltUrl(),
            "neo4j",
            NEO4J.getAdminPassword(),
            "neo4j"
        );
    }

    private static String nextPrefix() {
        return "rag_" + UUID.randomUUID().toString().replace("-", "") + "_";
    }
}
