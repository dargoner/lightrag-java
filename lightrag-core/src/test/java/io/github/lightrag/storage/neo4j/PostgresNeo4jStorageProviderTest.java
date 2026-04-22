package io.github.lightrag.storage.neo4j;

import io.github.lightrag.persistence.FileSnapshotStore;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import io.github.lightrag.storage.postgres.PostgresStorageProvider;
import io.github.lightrag.support.Neo4jTestContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresNeo4jStorageProviderTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @Container
    private static final Neo4jContainer<?> NEO4J = Neo4jTestContainers.create();

    @BeforeEach
    void resetSharedNeo4j() {
        try (var driver = org.neo4j.driver.GraphDatabase.driver(
            NEO4J.getBoltUrl(),
            org.neo4j.driver.AuthTokens.basic("neo4j", NEO4J.getAdminPassword())
        );
             var session = driver.session(org.neo4j.driver.SessionConfig.forDatabase("neo4j"))) {
            session.executeWrite(tx -> {
                tx.run("MATCH (node) DETACH DELETE node");
                return null;
            });
        }
    }

    @Test
    void delegatesThroughInjectedGraphAdapterAndKeepsPostgresVectorStore() {
        var snapshotStore = new FileSnapshotStore();
        var tablePrefix = nextPrefix();
        var graphAdapter = new RecordingGraphStorageAdapter();

        try (var postgresProvider = new PostgresStorageProvider(newPostgresConfig(tablePrefix), snapshotStore);
             var provider = new PostgresNeo4jStorageProvider(postgresProvider, graphAdapter)) {
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

            assertThat(graphAdapter.applyCount()).isEqualTo(1);
            assertThat(postgresProvider.graphStore().loadEntity("entity-1")).isPresent();
            assertThat(postgresProvider.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));
            assertThat(provider.graphStore().loadEntity("entity-1")).isPresent();
            assertThat(provider.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)));
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
    void exposesGraphAdapterInjectionConstructor() throws Exception {
        assertThat(PostgresNeo4jStorageProvider.class.getConstructor(
            PostgresStorageProvider.class,
            GraphStorageAdapter.class
        )).isNotNull();
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
    void topLevelBatchGraphWritesUseSingleProjectionApplyPerRecordType() {
        var snapshotStore = new FileSnapshotStore();
        var tablePrefix = nextPrefix();
        var graphAdapter = new RecordingGraphStorageAdapter();

        try (var postgresProvider = new PostgresStorageProvider(newPostgresConfig(tablePrefix), snapshotStore);
             var provider = new PostgresNeo4jStorageProvider(postgresProvider, graphAdapter)) {
            var alice = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Researcher",
                List.of("A"),
                List.of("chunk-1")
            );
            var bob = new GraphStore.EntityRecord(
                "entity-2",
                "Bob",
                "person",
                "Engineer",
                List.of("B"),
                List.of("chunk-2")
            );
            var relation = new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-2",
                "works_with",
                "Alice works with Bob",
                0.9d,
                List.of("chunk-1")
            );

            provider.graphStore().saveEntities(List.of(alice, bob));
            provider.graphStore().saveRelations(List.of(relation));

            assertThat(graphAdapter.applyCount()).isEqualTo(2);
            assertThat(provider.graphStore().loadEntities(List.of("entity-2", "missing", "entity-1")))
                .containsExactly(bob, alice);
            assertThat(provider.graphStore().loadRelations(List.of("relation-1", "missing")))
                .containsExactly(relation);
        }
    }

    @Test
    void commitsAcrossPostgresAndNeo4jStores() {
        try (var provider = newProvider(new FileSnapshotStore())) {
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
            assertThat(provider.documentStatusStore().load("doc-1")).contains(
                new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "ok", null)
            );
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
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d)))),
            List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "snapshot", null))
        );

        try (var provider = newProvider(new FileSnapshotStore())) {
            provider.documentStore().save(new DocumentStore.DocumentRecord("doc-old", "Old", "Old", Map.of()));
            provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-old:0", "doc-old", "Old", 3, 0, Map.of()));
            provider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                "doc-old",
                DocumentStatus.PROCESSED,
                "old",
                null
            ));
            provider.graphStore().saveEntity(new GraphStore.EntityRecord("entity-old", "Old", "type", "Old", List.of(), List.of()));
            provider.graphStore().saveRelation(new GraphStore.RelationRecord("relation-old", "entity-old", "entity-old", "self", "Old", 0.1d, List.of()));
            provider.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-old:0", List.of(0.0d, 1.0d, 0.0d))));

            provider.restore(replacement);

            assertThat(provider.documentStore().list()).containsExactlyElementsOf(replacement.documents());
            assertThat(provider.chunkStore().list()).containsExactlyElementsOf(replacement.chunks());
            assertThat(provider.graphStore().allEntities()).containsExactlyElementsOf(replacement.entities());
            assertThat(provider.graphStore().allRelations()).containsExactlyElementsOf(replacement.relations());
            assertThat(provider.vectorStore().list("chunks")).containsExactlyElementsOf(replacement.vectors().get("chunks"));
            assertThat(provider.documentStatusStore().list()).containsExactlyElementsOf(replacement.documentStatuses());
            assertThat(provider.vectorStore().list("entities")).isEmpty();
        }
    }

    @Test
    void isolatesGraphReadsAndWritesAcrossWorkspaceScopedProviders() {
        try (var alpha = newProvider(new FileSnapshotStore(), nextPrefix(), "alpha");
             var beta = newProvider(new FileSnapshotStore(), nextPrefix(), "beta")) {
            var alphaEntity = new GraphStore.EntityRecord(
                "entity-1",
                "Alice",
                "person",
                "Alpha entity",
                List.of("A"),
                List.of("alpha-chunk")
            );
            var betaEntity = new GraphStore.EntityRecord(
                "entity-1",
                "Bob",
                "person",
                "Beta entity",
                List.of("B"),
                List.of("beta-chunk")
            );

            alpha.graphStore().saveEntity(alphaEntity);
            beta.graphStore().saveEntity(betaEntity);

            assertThat(alpha.graphStore().loadEntity("entity-1")).contains(alphaEntity);
            assertThat(beta.graphStore().loadEntity("entity-1")).contains(betaEntity);
            assertThat(alpha.graphStore().allEntities()).containsExactly(alphaEntity);
            assertThat(beta.graphStore().allEntities()).containsExactly(betaEntity);
        }
    }

    @Test
    void isolatesPostgresStoresAcrossWorkspaceScopedProvidersSharingBasePrefix() {
        var sharedPrefix = nextPrefix();
        try (var alpha = newProvider(new FileSnapshotStore(), sharedPrefix, "alpha");
             var beta = newProvider(new FileSnapshotStore(), sharedPrefix, "beta")) {
            alpha.documentStore().save(new DocumentStore.DocumentRecord("doc-alpha-1", "Alpha", "Body", Map.of("source", "alpha")));
            alpha.chunkStore().save(new ChunkStore.ChunkRecord("doc-alpha-1:0", "doc-alpha-1", "Body", 4, 0, Map.of("source", "alpha")));
            alpha.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-alpha-1:0", List.of(1.0d, 0.0d, 0.0d))));

            beta.documentStore().save(new DocumentStore.DocumentRecord("doc-beta-1", "Beta", "Body", Map.of("source", "beta")));
            beta.chunkStore().save(new ChunkStore.ChunkRecord("doc-beta-1:0", "doc-beta-1", "Body", 4, 0, Map.of("source", "beta")));
            beta.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-beta-1:0", List.of(0.0d, 1.0d, 0.0d))));

            assertThat(alpha.documentStore().list())
                .containsExactly(new DocumentStore.DocumentRecord("doc-alpha-1", "Alpha", "Body", Map.of("source", "alpha")));
            assertThat(alpha.chunkStore().list())
                .containsExactly(new ChunkStore.ChunkRecord("doc-alpha-1:0", "doc-alpha-1", "Body", 4, 0, Map.of("source", "alpha")));
            assertThat(alpha.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-alpha-1:0", List.of(1.0d, 0.0d, 0.0d)));

            assertThat(beta.documentStore().list())
                .containsExactly(new DocumentStore.DocumentRecord("doc-beta-1", "Beta", "Body", Map.of("source", "beta")));
            assertThat(beta.chunkStore().list())
                .containsExactly(new ChunkStore.ChunkRecord("doc-beta-1:0", "doc-beta-1", "Body", 4, 0, Map.of("source", "beta")));
            assertThat(beta.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-beta-1:0", List.of(0.0d, 1.0d, 0.0d)));
        }
    }

    @Test
    void restoreOnlyReplacesCurrentWorkspaceState() {
        var betaReplacement = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-beta-2", "Beta Snapshot", "Body", Map.of("source", "beta"))),
            List.of(new ChunkStore.ChunkRecord("doc-beta-2:0", "doc-beta-2", "Body", 4, 0, Map.of("source", "beta"))),
            List.of(new GraphStore.EntityRecord(
                "entity-beta-2",
                "Bert",
                "person",
                "Beta replacement",
                List.of("B2"),
                List.of("doc-beta-2:0")
            )),
            List.of(new GraphStore.RelationRecord(
                "relation-beta-2",
                "entity-beta-2",
                "entity-beta-3",
                "knows",
                "Bert knows Bella",
                0.8d,
                List.of("doc-beta-2:0")
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-beta-2:0", List.of(0.0d, 1.0d, 0.0d))))
        );

        try (var alpha = newProvider(new FileSnapshotStore(), nextPrefix(), "alpha");
             var beta = newProvider(new FileSnapshotStore(), nextPrefix(), "beta")) {
            alpha.documentStore().save(new DocumentStore.DocumentRecord("doc-alpha-1", "Alpha", "Body", Map.of("source", "alpha")));
            alpha.chunkStore().save(new ChunkStore.ChunkRecord("doc-alpha-1:0", "doc-alpha-1", "Body", 4, 0, Map.of("source", "alpha")));
            alpha.graphStore().saveEntity(new GraphStore.EntityRecord(
                "entity-shared",
                "Alice",
                "person",
                "Alpha entity",
                List.of("A"),
                List.of("doc-alpha-1:0")
            ));
            alpha.graphStore().saveRelation(new GraphStore.RelationRecord(
                "relation-alpha-1",
                "entity-shared",
                "entity-alpha-2",
                "knows",
                "Alice knows Aaron",
                0.9d,
                List.of("doc-alpha-1:0")
            ));
            alpha.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-alpha-1:0", List.of(1.0d, 0.0d, 0.0d))));

            beta.documentStore().save(new DocumentStore.DocumentRecord("doc-beta-1", "Beta", "Body", Map.of("source", "beta")));
            beta.chunkStore().save(new ChunkStore.ChunkRecord("doc-beta-1:0", "doc-beta-1", "Body", 4, 0, Map.of("source", "beta")));
            beta.graphStore().saveEntity(new GraphStore.EntityRecord(
                "entity-shared",
                "Bob",
                "person",
                "Beta entity",
                List.of("B"),
                List.of("doc-beta-1:0")
            ));
            beta.graphStore().saveRelation(new GraphStore.RelationRecord(
                "relation-beta-1",
                "entity-shared",
                "entity-beta-2",
                "knows",
                "Bob knows Ben",
                0.7d,
                List.of("doc-beta-1:0")
            ));
            beta.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-beta-1:0", List.of(0.0d, 1.0d, 0.0d))));

            beta.restore(betaReplacement);

            assertThat(alpha.documentStore().list())
                .containsExactly(new DocumentStore.DocumentRecord("doc-alpha-1", "Alpha", "Body", Map.of("source", "alpha")));
            assertThat(alpha.chunkStore().list())
                .containsExactly(new ChunkStore.ChunkRecord("doc-alpha-1:0", "doc-alpha-1", "Body", 4, 0, Map.of("source", "alpha")));
            assertThat(alpha.graphStore().allEntities())
                .containsExactly(new GraphStore.EntityRecord(
                    "entity-shared",
                    "Alice",
                    "person",
                    "Alpha entity",
                    List.of("A"),
                    List.of("doc-alpha-1:0")
                ));
            assertThat(alpha.graphStore().allRelations())
                .containsExactly(new GraphStore.RelationRecord(
                    "relation-alpha-1",
                    "entity-shared",
                    "entity-alpha-2",
                    "knows",
                    "Alice knows Aaron",
                    0.9d,
                    List.of("doc-alpha-1:0")
                ));
            assertThat(alpha.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("doc-alpha-1:0", List.of(1.0d, 0.0d, 0.0d)));

            assertThat(beta.documentStore().list()).containsExactlyElementsOf(betaReplacement.documents());
            assertThat(beta.chunkStore().list()).containsExactlyElementsOf(betaReplacement.chunks());
            assertThat(beta.graphStore().allEntities()).containsExactlyElementsOf(betaReplacement.entities());
            assertThat(beta.graphStore().allRelations()).containsExactlyElementsOf(betaReplacement.relations());
            assertThat(beta.vectorStore().list("chunks")).containsExactlyElementsOf(betaReplacement.vectors().get("chunks"));
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

    @Test
    void rollbackOnlyRestoresFailedWorkspaceWhenProvidersShareNeo4jDatabase() {
        var alphaLock = new ReentrantReadWriteLock(true);
        var betaLock = new ReentrantReadWriteLock(true);
        var alphaPrefix = nextPrefix();
        var betaPrefix = nextPrefix();
        var alphaEntity = new GraphStore.EntityRecord(
            "entity-1",
            "Alpha",
            "person",
            "Alpha entity",
            List.of(),
            List.of("alpha-doc:0")
        );
        var alphaRelation = new GraphStore.RelationRecord(
            "relation-1",
            "entity-1",
            "entity-2",
            "knows",
            "Alpha knows peer",
            0.9d,
            List.of("alpha-doc:0")
        );

        try (var alpha = newWorkspaceProvider(new FileSnapshotStore(), alphaPrefix, "alpha", alphaLock);
             var beta = newFailingWorkspaceProvider(new FileSnapshotStore(), betaPrefix, "beta", betaLock)) {
            alpha.documentStore().save(new DocumentStore.DocumentRecord("alpha-doc", "Alpha", "Body", Map.of()));
            alpha.chunkStore().save(new ChunkStore.ChunkRecord("alpha-doc:0", "alpha-doc", "Body", 4, 0, Map.of()));
            alpha.graphStore().saveEntity(alphaEntity);
            alpha.graphStore().saveRelation(alphaRelation);
            alpha.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("alpha-doc:0", List.of(1.0d, 0.0d, 0.0d))));

            assertThatThrownBy(() -> beta.writeAtomically(storage -> {
                storage.documentStore().save(new DocumentStore.DocumentRecord("beta-doc", "Beta", "Body", Map.of()));
                storage.chunkStore().save(new ChunkStore.ChunkRecord("beta-doc:0", "beta-doc", "Body", 4, 0, Map.of()));
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Beta",
                    "person",
                    "Beta entity",
                    List.of(),
                    List.of("beta-doc:0")
                ));
                storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                    "relation-1",
                    "entity-1",
                    "entity-3",
                    "knows",
                    "Beta knows peer",
                    0.7d,
                    List.of("beta-doc:0")
                ));
                storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("beta-doc:0", List.of(0.0d, 1.0d, 0.0d))));
                return null;
            }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");

            assertThat(alpha.documentStore().list())
                .containsExactly(new DocumentStore.DocumentRecord("alpha-doc", "Alpha", "Body", Map.of()));
            assertThat(alpha.chunkStore().list())
                .containsExactly(new ChunkStore.ChunkRecord("alpha-doc:0", "alpha-doc", "Body", 4, 0, Map.of()));
            assertThat(alpha.graphStore().allEntities()).containsExactly(alphaEntity);
            assertThat(alpha.graphStore().allRelations()).containsExactly(alphaRelation);
            assertThat(alpha.vectorStore().list("chunks"))
                .containsExactly(new VectorStore.VectorRecord("alpha-doc:0", List.of(1.0d, 0.0d, 0.0d)));

            assertThat(beta.documentStore().list()).isEmpty();
            assertThat(beta.chunkStore().list()).isEmpty();
            assertThat(beta.graphStore().allEntities()).isEmpty();
            assertThat(beta.graphStore().allRelations()).isEmpty();
            assertThat(beta.vectorStore().list("chunks")).isEmpty();
        }
    }

    @Test
    void blocksSameWorkspaceWritesUntilProjectionFailureRollbackCompletes() throws Exception {
        var tablePrefix = nextPrefix();
        var snapshotStarted = new CountDownLatch(1);
        var releaseSnapshot = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var writerFinished = new CountDownLatch(1);
        var failedWrite = new AtomicReference<Throwable>();
        var concurrentWriteFailure = new AtomicReference<Throwable>();

        try (var blockingGraphStore = newWorkspaceGraphStore("default");
             var postgresProvider = new PostgresStorageProvider(newPostgresConfig(tablePrefix), new FileSnapshotStore());
             var failingProvider = new PostgresNeo4jStorageProvider(
                 postgresProvider,
                 new GraphStorageAdapter() {
                     private final GraphStorageAdapter delegate = new Neo4jGraphStorageAdapter(blockingGraphStore);

                     @Override
                     public GraphStore graphStore() {
                         return delegate.graphStore();
                     }

                     @Override
                     public GraphSnapshot captureSnapshot() {
                         snapshotStarted.countDown();
                         try {
                             if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) {
                                 throw new IllegalStateException("timed out waiting to release graph snapshot");
                             }
                         } catch (InterruptedException exception) {
                             Thread.currentThread().interrupt();
                             throw new IllegalStateException("interrupted while waiting to release graph snapshot", exception);
                         }
                         return delegate.captureSnapshot();
                     }

                     @Override
                     public void apply(StagedGraphWrites writes) {
                         throw new IllegalStateException("projection failed");
                     }

                     @Override
                     public void restore(GraphSnapshot snapshot) {
                         delegate.restore(snapshot);
                     }

                     @Override
                     public void close() {
                         delegate.close();
                     }
                 }
             );
             var concurrentProvider = newWorkspaceProvider(
                 new FileSnapshotStore(),
                 tablePrefix,
                 "default",
                 new ReentrantReadWriteLock(true)
             )) {
            failingProvider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "body", Map.of("seed", "true")));
            failingProvider.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                "doc-0",
                DocumentStatus.PROCESSED,
                "seeded",
                null
            ));

            var failingThread = new Thread(() -> {
                try {
                    failingProvider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "Incoming", "body", Map.of()));
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
                            List.of()
                        ));
                        return null;
                    });
                } catch (Throwable throwable) {
                    failedWrite.set(throwable);
                }
            });
            failingThread.start();

            assertThat(snapshotStarted.await(10, TimeUnit.SECONDS)).isTrue();

            var concurrentWriter = new Thread(() -> {
                writerStarted.countDown();
                try {
                    concurrentProvider.writeAtomically(storage -> {
                        storage.documentStore().save(new DocumentStore.DocumentRecord("doc-2", "Concurrent", "body", Map.of()));
                        storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                            "doc-2",
                            DocumentStatus.PROCESSED,
                            "concurrent",
                            null
                        ));
                        return null;
                    });
                } catch (Throwable throwable) {
                    concurrentWriteFailure.set(throwable);
                } finally {
                    writerFinished.countDown();
                }
            });
            concurrentWriter.start();

            assertThat(writerStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(writerFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

            releaseSnapshot.countDown();

            failingThread.join(Duration.ofSeconds(10).toMillis());
            concurrentWriter.join(Duration.ofSeconds(10).toMillis());

            assertThat(failingThread.isAlive()).isFalse();
            assertThat(concurrentWriter.isAlive()).isFalse();
            assertThat(failedWrite.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("projection failed");
            assertThat(concurrentWriteFailure.get()).isNull();
            assertThat(concurrentProvider.documentStore().list())
                .containsExactly(
                    new DocumentStore.DocumentRecord("doc-0", "Seed", "body", Map.of("seed", "true")),
                    new DocumentStore.DocumentRecord("doc-2", "Concurrent", "body", Map.of())
                );
            assertThat(concurrentProvider.documentStatusStore().list())
                .containsExactly(
                    new DocumentStatusStore.StatusRecord("doc-0", DocumentStatus.PROCESSED, "seeded", null),
                    new DocumentStatusStore.StatusRecord("doc-2", DocumentStatus.PROCESSED, "concurrent", null)
                );
        }
    }

    @Test
    void writeLockIsScopedPerWorkspaceInsteadOfBlockingOtherWorkspaces() throws Exception {
        var alphaLock = new ReentrantReadWriteLock(true);
        var betaLock = new ReentrantReadWriteLock(true);
        var projectionStarted = new CountDownLatch(1);
        var releaseAlphaProjection = new CountDownLatch(1);
        try (var alphaProjectionGraphStore = newWorkspaceGraphStore("alpha");
             var alpha = newWorkspaceProvider(
                 new FileSnapshotStore(),
                 nextPrefix(),
                 "alpha",
                 alphaLock,
                 (entities, relations) -> {
                     projectionStarted.countDown();
                     try {
                         if (!releaseAlphaProjection.await(10, TimeUnit.SECONDS)) {
                             throw new IllegalStateException("timed out waiting to release alpha projection");
                         }
                     } catch (InterruptedException exception) {
                         Thread.currentThread().interrupt();
                         throw new IllegalStateException("interrupted while waiting to release alpha projection", exception);
                     }
                     for (var entity : entities) {
                         alphaProjectionGraphStore.saveEntity(entity);
                     }
                     for (var relation : relations) {
                         alphaProjectionGraphStore.saveRelation(relation);
                     }
                 }
             );
             var beta = newWorkspaceProvider(new FileSnapshotStore(), nextPrefix(), "beta", betaLock)) {

            var alphaThread = new Thread(() -> alpha.writeAtomically(storage -> {
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Alpha",
                    "person",
                    "Alpha entity",
                    List.of(),
                    List.of("alpha-doc:0")
                ));
                return null;
            }));
            alphaThread.start();

            assertThat(projectionStarted.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatCode(() -> beta.writeAtomically(storage -> {
                storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-1",
                    "Beta",
                    "person",
                    "Beta entity",
                    List.of(),
                    List.of("beta-doc:0")
                ));
                return null;
            })).doesNotThrowAnyException();

            assertThat(beta.graphStore().loadEntity("entity-1")).isPresent();

            releaseAlphaProjection.countDown();
            alphaThread.join(Duration.ofSeconds(10).toMillis());
            assertThat(alphaThread.isAlive()).isFalse();
        }
    }

    private static Neo4jGraphStore newGraphStore() {
        return new Neo4jGraphStore(newNeo4jConfig());
    }

    private static WorkspaceScopedNeo4jGraphStore newWorkspaceGraphStore(String workspaceId) {
        return new WorkspaceScopedNeo4jGraphStore(newNeo4jConfig(), new io.github.lightrag.api.WorkspaceScope(workspaceId));
    }

    private static PostgresNeo4jStorageProvider newProvider(SnapshotStore snapshotStore) {
        return newProvider(snapshotStore, nextPrefix());
    }

    private static PostgresNeo4jStorageProvider newProvider(SnapshotStore snapshotStore, String tablePrefix) {
        return newProvider(snapshotStore, tablePrefix, "default");
    }

    private static PostgresNeo4jStorageProvider newProvider(
        SnapshotStore snapshotStore,
        String tablePrefix,
        String workspaceId
    ) {
        return new PostgresNeo4jStorageProvider(
            newPostgresConfig(tablePrefix),
            newNeo4jConfig(),
            snapshotStore,
            new WorkspaceScope(workspaceId)
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
            newWorkspaceGraphAdapter(
                "default",
                (entities, relations) -> {
                    throw new IllegalStateException("projection failed");
                }
            ),
            new ReentrantReadWriteLock(true)
        );
    }

    private static GraphStorageAdapter newWorkspaceGraphAdapter(String workspaceId) {
        return newWorkspaceGraphAdapter(workspaceId, null);
    }

    private static GraphStorageAdapter newWorkspaceGraphAdapter(
        String workspaceId,
        GraphProjectionApplier projectionApplier
    ) {
        var delegate = new Neo4jGraphStorageAdapter(newWorkspaceGraphStore(workspaceId));
        return new GraphStorageAdapter() {
            @Override
            public GraphStore graphStore() {
                return delegate.graphStore();
            }

            @Override
            public GraphSnapshot captureSnapshot() {
                return delegate.captureSnapshot();
            }

            @Override
            public void apply(StagedGraphWrites writes) {
                if (projectionApplier == null) {
                    delegate.apply(writes);
                    return;
                }
                projectionApplier.apply(writes.entities(), writes.relations());
            }

            @Override
            public void restore(GraphSnapshot snapshot) {
                delegate.restore(snapshot);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    @FunctionalInterface
    private interface GraphProjectionApplier {
        void apply(
            java.util.Collection<GraphStore.EntityRecord> entities,
            java.util.Collection<GraphStore.RelationRecord> relations
        );
    }

    private static PostgresNeo4jStorageProvider newWorkspaceProvider(
        SnapshotStore snapshotStore,
        String tablePrefix,
        String workspaceId,
        ReentrantReadWriteLock lock
    ) {
        return newWorkspaceProvider(
            snapshotStore,
            tablePrefix,
            workspaceId,
            lock,
            null
        );
    }

    private static PostgresNeo4jStorageProvider newWorkspaceProvider(
        SnapshotStore snapshotStore,
        String tablePrefix,
        String workspaceId,
        ReentrantReadWriteLock lock,
        GraphProjectionApplier projectionApplier
    ) {
        return new PostgresNeo4jStorageProvider(
            new PostgresStorageProvider(
                newPostgresConfig(tablePrefix),
                snapshotStore
            ),
            newWorkspaceGraphAdapter(workspaceId, projectionApplier),
            lock
        );
    }

    private static PostgresNeo4jStorageProvider newFailingWorkspaceProvider(
        SnapshotStore snapshotStore,
        String tablePrefix,
        String workspaceId,
        ReentrantReadWriteLock lock
    ) {
        return newWorkspaceProvider(
            snapshotStore,
            tablePrefix,
            workspaceId,
            lock,
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

    private static final class RecordingGraphStorageAdapter implements GraphStorageAdapter {
        private final java.util.LinkedHashMap<String, GraphStore.EntityRecord> entities = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<String, GraphStore.RelationRecord> relations = new java.util.LinkedHashMap<>();
        private int applyCount;

        @Override
        public GraphStore graphStore() {
            return new GraphStore() {
                @Override
                public void saveEntity(EntityRecord entity) {
                    entities.put(entity.id(), entity);
                }

                @Override
                public void saveRelation(RelationRecord relation) {
                    relations.put(relation.id(), relation);
                }

                @Override
                public java.util.Optional<EntityRecord> loadEntity(String entityId) {
                    return java.util.Optional.ofNullable(entities.get(entityId));
                }

                @Override
                public java.util.Optional<RelationRecord> loadRelation(String relationId) {
                    return java.util.Optional.ofNullable(relations.get(relationId));
                }

                @Override
                public java.util.List<EntityRecord> allEntities() {
                    return java.util.List.copyOf(entities.values());
                }

                @Override
                public java.util.List<RelationRecord> allRelations() {
                    return java.util.List.copyOf(relations.values());
                }

                @Override
                public java.util.List<RelationRecord> findRelations(String entityId) {
                    return relations.values().stream()
                        .filter(relation -> relation.srcId().equals(entityId) || relation.tgtId().equals(entityId))
                        .toList();
                }
            };
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            return new GraphSnapshot(List.copyOf(entities.values()), List.copyOf(relations.values()));
        }

        @Override
        public void apply(StagedGraphWrites writes) {
            applyCount++;
            for (var entity : writes.entities()) {
                entities.put(entity.id(), entity);
            }
            for (var relation : writes.relations()) {
                relations.put(relation.id(), relation);
            }
        }

        @Override
        public void restore(GraphSnapshot snapshot) {
            entities.clear();
            relations.clear();
            snapshot.entities().forEach(entity -> entities.put(entity.id(), entity));
            snapshot.relations().forEach(relation -> relations.put(relation.id(), relation));
        }

        int applyCount() {
            return applyCount;
        }
    }
}
