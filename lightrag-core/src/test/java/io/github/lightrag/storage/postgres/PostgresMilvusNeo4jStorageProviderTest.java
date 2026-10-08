package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.KnowledgeGraphView;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.exception.StorageException;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStorageAdapter;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageLockManager;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.neo4j.Neo4jGraphSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.lightrag.support.RelationIds.relationId;

@Testcontainers
class PostgresMilvusNeo4jStorageProviderTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = newPostgresContainer();

    @Test
    void mirroringGraphStoreOverridesBatchGraphLoads() throws Exception {
        Class<?> mirroringGraphStoreClass = Class.forName(
            "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProvider$MirroringGraphStore"
        );

        Method loadEntities = mirroringGraphStoreClass.getDeclaredMethod("loadEntities", List.class);
        Method loadRelations = mirroringGraphStoreClass.getDeclaredMethod("loadRelations", List.class);
        Method findRelations = mirroringGraphStoreClass.getDeclaredMethod("findRelations", List.class);
        Class<?> lockedChunkStoreClass = Class.forName(
            "io.github.lightrag.storage.postgres.PostgresMilvusNeo4jStorageProvider$LockedChunkStore"
        );
        Method loadAllChunks = lockedChunkStoreClass.getDeclaredMethod("loadAll", List.class);

        assertThat(loadEntities.getReturnType()).isEqualTo(List.class);
        assertThat(loadRelations.getReturnType()).isEqualTo(List.class);
        assertThat(findRelations.getReturnType()).isEqualTo(Map.class);
        assertThat(loadAllChunks.getReturnType()).isEqualTo(Map.class);
    }

    @Test
    void forwardsKnowledgeGraphThroughTheMirroringGraphStore() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var graphProjection = new RecordingGraphProjection();
            var stubView = new KnowledgeGraphView(List.of(), List.of(), true);
            graphProjection.stubKnowledgeGraph(stubView);

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphProjection,
                new RecordingMilvusProjection(),
                new ReentrantReadWriteLock(true)
            )) {
                var view = provider.graphStore().getKnowledgeGraph("c3", 2, 7);

                assertThat(graphProjection.knowledgeGraphCalls()).containsExactly("c3/2/7");
                assertThat(view).isSameAs(stubView);
            }
        }
    }

    @Test
    void exposesStableTopLevelStoresAndDistinctAtomicViewStores() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                new ReentrantReadWriteLock(true)
            )) {
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
    }

    @Test
    void honorsProvidedLockForTopLevelDocumentWrites() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var providerLock = new ReentrantReadWriteLock(true);

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                providerLock
            )) {
                var started = new CountDownLatch(1);
                var finished = new CountDownLatch(1);
                var failure = new AtomicReference<Throwable>();

                providerLock.writeLock().lock();
                var writer = new Thread(() -> {
                    started.countDown();
                    try {
                        provider.documentStore().save(new DocumentStore.DocumentRecord("doc-locked", "Title", "Body", Map.of()));
                    } catch (Throwable throwable) {
                        failure.set(throwable);
                    } finally {
                        finished.countDown();
                    }
                });
                writer.start();

                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(finished.await(200, TimeUnit.MILLISECONDS)).isFalse();
                providerLock.writeLock().unlock();

                assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
                writer.join(1000);
                assertThat(failure.get()).isNull();
                assertThat(provider.documentStore().load("doc-locked")).isPresent();
            } finally {
                if (providerLock.isWriteLockedByCurrentThread()) {
                    providerLock.writeLock().unlock();
                }
            }
        }
    }

    @Test
    void usesInjectedStorageLockManagerForWorkspaceWrites() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new RecordingStorageLockManager();
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                externalLock
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-external-lock", "Title", "Body", Map.of()));
                    return null;
                });

                assertThat(provider.documentStore().load("doc-external-lock")).isPresent();
                assertThat(externalLock.exclusiveCalls()).isEqualTo(1);
                assertThat(externalLock.activeExclusiveCalls()).isZero();
            }
        }
    }

    @Test
    void usesInjectedStorageLockManagerForAdaptersFamilyWrites() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new RecordingStorageLockManager();
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphStorageAdapter(),
                new RecordingVectorStorageAdapter(),
                externalLock
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-adapters-lock", "Title", "Body", Map.of()));
                    return null;
                });

                assertThat(provider.documentStore().load("doc-adapters-lock")).isPresent();
                assertThat(externalLock.exclusiveCalls()).isEqualTo(1);
                assertThat(externalLock.activeExclusiveCalls()).isZero();
            }
        }
    }

    @Test
    void keepsReadsAvailableWhileAWriteWaitsForTheExternalStorageLock() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new BlockingStorageLockManager();
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                externalLock
            )) {
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-existing", "Title", "Body", Map.of()));
                externalLock.startBlocking();

                var writeFinished = new CountDownLatch(1);
                var writeFailure = new AtomicReference<Throwable>();
                var writer = new Thread(() -> {
                    try {
                        provider.documentStore().save(new DocumentStore.DocumentRecord("doc-deferred", "Title", "Body", Map.of()));
                    } catch (Throwable throwable) {
                        writeFailure.set(throwable);
                    } finally {
                        writeFinished.countDown();
                    }
                });
                var readFinished = new CountDownLatch(1);
                var readFailure = new AtomicReference<Throwable>();
                var readResult = new AtomicReference<Optional<DocumentStore.DocumentRecord>>();
                var reader = new Thread(() -> {
                    try {
                        readResult.set(provider.documentStore().load("doc-existing"));
                    } catch (Throwable throwable) {
                        readFailure.set(throwable);
                    } finally {
                        readFinished.countDown();
                    }
                });

                writer.start();
                try {
                    assertThat(externalLock.awaitEntered(10, TimeUnit.SECONDS)).isTrue();

                    reader.start();
                    // The local read lock must stay available while the write waits for the external lock.
                    assertThat(readFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    reader.join(1000);
                    assertThat(readFailure.get()).isNull();
                    assertThat(readResult.get()).isPresent();

                    externalLock.release();
                    assertThat(writeFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    writer.join(1000);
                    assertThat(writeFailure.get()).isNull();
                    assertThat(provider.documentStore().load("doc-deferred")).isPresent();
                } finally {
                    externalLock.release();
                    writer.join(5000);
                    reader.join(5000);
                }
            }
        }
    }

    @Test
    void releasesReadWaitersByInterruptionWithExplicitFailure() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var providerLock = new ReentrantReadWriteLock(true);
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                providerLock
            )) {
                var readFinished = new CountDownLatch(1);
                var readFailure = new AtomicReference<Throwable>();
                var reader = new Thread(() -> {
                    try {
                        provider.documentStore().load("doc-interrupted");
                    } catch (Throwable throwable) {
                        readFailure.set(throwable);
                    } finally {
                        readFinished.countDown();
                    }
                });

                providerLock.writeLock().lock();
                try {
                    reader.start();
                    awaitQueuedOn(providerLock, reader);
                    assertThat(readFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

                    reader.interrupt();

                    assertThat(readFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    reader.join(1000);
                    assertThat(readFailure.get()).isInstanceOf(StorageException.class);
                    assertThat(readFailure.get()).hasCauseInstanceOf(InterruptedException.class);
                    assertThat(reader.isInterrupted()).isTrue();
                } finally {
                    if (providerLock.isWriteLockedByCurrentThread()) {
                        providerLock.writeLock().unlock();
                    }
                    reader.join(5000);
                }
            }
        }
    }

    @Test
    void releasesWriteWaitersByInterruptionWithExplicitFailure() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var providerLock = new ReentrantReadWriteLock(true);
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                providerLock
            )) {
                var writeFinished = new CountDownLatch(1);
                var writeFailure = new AtomicReference<Throwable>();
                var writer = new Thread(() -> {
                    try {
                        provider.documentStore().save(new DocumentStore.DocumentRecord("doc-interrupted", "Title", "Body", Map.of()));
                    } catch (Throwable throwable) {
                        writeFailure.set(throwable);
                    } finally {
                        writeFinished.countDown();
                    }
                });

                providerLock.writeLock().lock();
                try {
                    writer.start();
                    awaitQueuedOn(providerLock, writer);
                    assertThat(writeFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();

                    writer.interrupt();

                    assertThat(writeFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    writer.join(1000);
                    assertThat(writeFailure.get()).isInstanceOf(StorageException.class);
                    assertThat(writeFailure.get()).hasCauseInstanceOf(InterruptedException.class);
                    assertThat(writer.isInterrupted()).isTrue();
                } finally {
                    if (providerLock.isWriteLockedByCurrentThread()) {
                        providerLock.writeLock().unlock();
                    }
                    writer.join(5000);
                }
            }
        }
    }

    @Test
    void releasesExternalLockWhenAnInterruptedWriterWaitsBehindAReader() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new RecordingStorageLockManager();
            var selectStarted = new CountDownLatch(1);
            var allowSelectToFinish = new CountDownLatch(1);
            DataSource blockingDataSource = blockingChunkSelectDataSource(
                dataSource,
                config,
                selectStarted,
                allowSelectToFinish
            );
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                blockingDataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                externalLock
            )) {
                provider.chunkStore().save(new ChunkStore.ChunkRecord(
                    "doc-1:0",
                    "doc-1",
                    "Body",
                    4,
                    0,
                    Map.of("source", "test")
                ));

                var readResult = new AtomicReference<Optional<ChunkStore.ChunkRecord>>();
                var readFailure = new AtomicReference<Throwable>();
                var reader = new Thread(() -> {
                    try {
                        readResult.set(provider.chunkStore().load("doc-1:0"));
                    } catch (Throwable throwable) {
                        readFailure.set(throwable);
                    }
                });
                reader.start();
                assertThat(selectStarted.await(5, TimeUnit.SECONDS)).isTrue();

                var writeFinished = new CountDownLatch(1);
                var writeFailure = new AtomicReference<Throwable>();
                var writer = new Thread(() -> {
                    try {
                        provider.documentStore().save(new DocumentStore.DocumentRecord("doc-w", "Title", "Body", Map.of()));
                    } catch (Throwable throwable) {
                        writeFailure.set(throwable);
                    } finally {
                        writeFinished.countDown();
                    }
                });
                writer.start();
                try {
                    // The writer must already hold the external lock while the parked reader keeps it off the local lock.
                    awaitActiveExclusiveCalls(externalLock, 1);

                    writer.interrupt();

                    assertThat(writeFinished.await(5, TimeUnit.SECONDS)).isTrue();
                    writer.join(1000);
                    assertThat(writeFailure.get()).isInstanceOf(StorageException.class);
                    assertThat(writeFailure.get()).hasCauseInstanceOf(InterruptedException.class);
                    assertThat(externalLock.activeExclusiveCalls()).isZero();

                    allowSelectToFinish.countDown();
                    reader.join(5000);
                    assertThat(readFailure.get()).isNull();
                    assertThat(readResult.get()).isPresent();

                    provider.documentStore().save(new DocumentStore.DocumentRecord("doc-after", "Title", "Body", Map.of()));
                    assertThat(provider.documentStore().load("doc-after")).isPresent();
                } finally {
                    allowSelectToFinish.countDown();
                    writer.join(5000);
                    reader.join(5000);
                }
            }
        }
    }

    @Test
    void supportsReentrantNestedWritesUnderTheExternalLock() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new ExclusiveStorageLockManager();
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                externalLock
            )) {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(new DocumentStore.DocumentRecord("doc-outer", "Title", "Body", Map.of()));
                    provider.documentStore().save(new DocumentStore.DocumentRecord("doc-nested", "Title", "Body", Map.of()));
                    return null;
                });

                assertThat(externalLock.exclusiveCalls()).isEqualTo(2);
                assertThat(externalLock.maxConcurrentThreads()).isEqualTo(1);
                assertThat(provider.documentStore().load("doc-outer")).isPresent();
                assertThat(provider.documentStore().load("doc-nested")).isPresent();
            }
        }
    }

    @Test
    void serializesWritersFromDifferentProvidersUnderTheSharedExternalLock() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var externalLock = new ExclusiveStorageLockManager();
            try (
                var first = new PostgresMilvusNeo4jStorageProvider(
                    dataSource,
                    config,
                    new InMemorySnapshotStore(),
                    new WorkspaceScope("default"),
                    new RecordingGraphProjection(),
                    new RecordingMilvusProjection(),
                    externalLock
                );
                var second = new PostgresMilvusNeo4jStorageProvider(
                    dataSource,
                    config,
                    new InMemorySnapshotStore(),
                    new WorkspaceScope("default"),
                    new RecordingGraphProjection(),
                    new RecordingMilvusProjection(),
                    externalLock
                )
            ) {
                var firstFailure = new AtomicReference<Throwable>();
                var secondFailure = new AtomicReference<Throwable>();
                var firstFinished = new CountDownLatch(1);
                var secondFinished = new CountDownLatch(1);

                var firstWriter = new Thread(() -> {
                    try {
                        first.writeAtomically(storage -> {
                            pause(400);
                            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-first", "Title", "Body", Map.of()));
                            return null;
                        });
                    } catch (Throwable throwable) {
                        firstFailure.set(throwable);
                    } finally {
                        firstFinished.countDown();
                    }
                });
                var secondWriter = new Thread(() -> {
                    try {
                        second.writeAtomically(storage -> {
                            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-second", "Title", "Body", Map.of()));
                            return null;
                        });
                    } catch (Throwable throwable) {
                        secondFailure.set(throwable);
                    } finally {
                        secondFinished.countDown();
                    }
                });

                firstWriter.start();
                try {
                    assertThat(externalLock.awaitSupplierEntered(5, TimeUnit.SECONDS)).isTrue();
                    secondWriter.start();

                    assertThat(firstFinished.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(secondFinished.await(10, TimeUnit.SECONDS)).isTrue();
                    firstWriter.join(1000);
                    secondWriter.join(1000);
                    assertThat(firstFailure.get()).isNull();
                    assertThat(secondFailure.get()).isNull();
                    assertThat(externalLock.exclusiveCalls()).isEqualTo(2);
                    assertThat(externalLock.maxConcurrentThreads()).isEqualTo(1);
                    assertThat(first.documentStore().load("doc-first")).isPresent();
                    assertThat(second.documentStore().load("doc-second")).isPresent();
                } finally {
                    firstWriter.join(5000);
                    secondWriter.join(5000);
                }
            }
        }
    }

    @Test
    void queryReadsDoNotHoldWorkspaceAdvisoryLock() throws Exception {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var selectStarted = new CountDownLatch(1);
            var allowSelectToFinish = new CountDownLatch(1);
            DataSource blockingDataSource = blockingChunkSelectDataSource(
                dataSource,
                config,
                selectStarted,
                allowSelectToFinish
            );
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                blockingDataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                new ReentrantReadWriteLock(true)
            )) {
                provider.chunkStore().save(new ChunkStore.ChunkRecord(
                    "doc-1:0",
                    "doc-1",
                    "Body",
                    4,
                    0,
                    Map.of("source", "test")
                ));

                var loadResult = new AtomicReference<Optional<ChunkStore.ChunkRecord>>();
                var loadFailure = new AtomicReference<Throwable>();
                var reader = new Thread(() -> {
                    try {
                        loadResult.set(provider.chunkStore().load("doc-1:0"));
                    } catch (Throwable throwable) {
                        loadFailure.set(throwable);
                    }
                });
                reader.start();

                assertThat(selectStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(tryExclusiveAdvisoryLock(config)).isTrue();

                allowSelectToFinish.countDown();
                reader.join(5000);

                assertThat(loadFailure.get()).isNull();
                assertThat(loadResult.get()).isPresent();
            }
        }
    }

    @Test
    void persistsDocumentGraphStateAcrossProviderRestart() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            var graphAdapter = new RecordingGraphStorageAdapter();
            var vectorAdapter = new RecordingVectorStorageAdapter();
            var snapshot = new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
                "doc-1",
                1,
                SnapshotStatus.READY,
                SnapshotSource.PRIMARY_EXTRACTION,
                1,
                Instant.parse("2026-04-12T10:00:00Z"),
                Instant.parse("2026-04-12T10:00:01Z"),
                null
            );
            var chunkSnapshot = new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                "doc-1",
                "doc-1:0",
                0,
                "hash-doc-1:0",
                ChunkExtractStatus.SUCCEEDED,
                List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord("Alice", "person", "Alice", List.of())),
                List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord("Alice", "Bob", "works_with", "works with", 1.0d)),
                Instant.parse("2026-04-12T10:00:02Z"),
                null
            );
            var documentJournal = new DocumentGraphJournalStore.DocumentGraphJournal(
                "doc-1",
                1,
                GraphMaterializationStatus.MERGED,
                GraphMaterializationMode.AUTO,
                1,
                1,
                1,
                1,
                FailureStage.FINALIZING,
                Instant.parse("2026-04-12T10:00:03Z"),
                Instant.parse("2026-04-12T10:00:04Z"),
                null
            );
            var chunkJournal = new DocumentGraphJournalStore.ChunkGraphJournal(
                "doc-1",
                "doc-1:0",
                1,
                ChunkMergeStatus.SUCCEEDED,
                ChunkGraphStatus.MATERIALIZED,
                List.of("alice"),
                List.of(relationId("alice", "bob")),
                List.of("alice"),
                List.of(relationId("alice", "bob")),
                FailureStage.FINALIZING,
                Instant.parse("2026-04-12T10:00:05Z"),
                null
            );

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphAdapter,
                vectorAdapter
            )) {
                provider.documentGraphSnapshotStore().saveDocument(snapshot);
                provider.documentGraphSnapshotStore().saveChunks("doc-1", List.of(chunkSnapshot));
                provider.documentGraphJournalStore().appendDocument(documentJournal);
                provider.documentGraphJournalStore().appendChunks("doc-1", List.of(chunkJournal));
            }

            try (var reopened = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphAdapter,
                vectorAdapter
            )) {
                assertThat(reopened.documentGraphSnapshotStore().loadDocument("doc-1")).contains(snapshot);
                assertThat(reopened.documentGraphSnapshotStore().listChunks("doc-1")).containsExactly(chunkSnapshot);
                assertThat(reopened.documentGraphJournalStore().listDocumentJournals("doc-1")).containsExactly(documentJournal);
                assertThat(reopened.documentGraphJournalStore().listChunkJournals("doc-1")).containsExactly(chunkJournal);
            }
        }
    }

    @Test
    void delegatesAtomicWriteToStorageCoordinatorAndPersistsPostgresGraphBaseline() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
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
                assertThat(countRows(dataSource, config, "documents", "doc-1")).isEqualTo(1);
                assertThat(countRows(dataSource, config, "chunks", "doc-1:0")).isEqualTo(1);
            }
        }
    }

    @Test
    void writeAtomicallyOmitsFullWorkspaceSnapshots() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            RecordingGraphStorageAdapter graphAdapter = new RecordingGraphStorageAdapter();
            RecordingVectorStorageAdapter vectorAdapter = new RecordingVectorStorageAdapter();
            var expectedRelationId = relationId("entity-1", "entity-2");

            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                graphAdapter,
                vectorAdapter
            )) {
                provider.writeAtomically(storage -> {
                    storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                        "entity-1",
                        "Alice",
                        "person",
                        "Researcher",
                        List.of("A"),
                        List.of("doc-1:0")
                    ));
                    storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                        expectedRelationId,
                        "entity-1",
                        "entity-2",
                        "knows",
                        "Alice knows Bob",
                        1.0d,
                        List.of("doc-1:0")
                    ));
                    storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d, 0.0d))));
                    return null;
                });

                assertThat(graphAdapter.applyCount()).isEqualTo(1);
                assertThat(vectorAdapter.applyCount()).isEqualTo(1);
                assertThat(graphAdapter.captureSnapshotCount()).isZero();
                assertThat(vectorAdapter.captureSnapshotCount()).isZero();
                assertThat(graphAdapter.capturedEntityIds()).containsExactly("entity-1");
                assertThat(graphAdapter.capturedRelationIds()).containsExactly(expectedRelationId);
                assertThat(vectorAdapter.capturedVectorIdsByNamespace())
                    .containsOnlyKeys("chunks");
                assertThat(vectorAdapter.capturedVectorIdsByNamespace())
                    .containsEntry("chunks", List.of("doc-1:0"));
            }
        }
    }


    @Test
    void commitsAcrossPostgresMilvusAndNeo4jStoresWithoutPgvector() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
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
    void persistsTaskStoresWhenUsingPostgresMilvusNeo4jProviderBootstrap() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
            try (var provider = new PostgresMilvusNeo4jStorageProvider(
                dataSource,
                config,
                new InMemorySnapshotStore(),
                new WorkspaceScope("default"),
                new RecordingGraphProjection(),
                new RecordingMilvusProjection(),
                new ReentrantReadWriteLock(true)
            )) {
                provider.taskStore().save(new io.github.lightrag.storage.TaskStore.TaskRecord(
                    "task-1",
                    "default",
                    TaskType.INGEST_DOCUMENTS,
                    TaskStatus.PENDING,
                    java.time.Instant.parse("2026-04-09T00:00:00Z"),
                    null,
                    null,
                    "queued",
                    null,
                    false,
                    Map.of("documentCount", "1")
                ));
                provider.taskStageStore().save(new io.github.lightrag.storage.TaskStageStore.TaskStageRecord(
                    "task-1",
                    TaskStage.PREPARING,
                    TaskStageStatus.RUNNING,
                    1,
                    java.time.Instant.parse("2026-04-09T00:00:01Z"),
                    null,
                    "starting",
                    null
                ));
                provider.taskDocumentStore().save(new io.github.lightrag.storage.TaskDocumentStore.TaskDocumentRecord(
                    "task-1",
                    "doc-1",
                    DocumentStatus.PROCESSING,
                    3,
                    2,
                    1,
                    3,
                    2,
                    1,
                    null
                ));

                assertThat(provider.taskStore().load("task-1")).isPresent();
                assertThat(provider.taskStageStore().listByTask("task-1"))
                    .extracting(io.github.lightrag.storage.TaskStageStore.TaskStageRecord::stage)
                    .containsExactly(TaskStage.PREPARING);
                assertThat(provider.taskDocumentStore().load("task-1", "doc-1")).isPresent();
            }
        }
    }

    @Test
    void rollsBackPostgresRowsWhenGraphProjectionFailsAfterCommit() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
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
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
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

    @Test
    void restorePathStillUsesRelationalSnapshotCapability() {
        var config = newConfig();
        try (var dataSource = newDataSource(config)) {
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
                provider.documentStore().save(new DocumentStore.DocumentRecord("doc-0", "Seed", "seed", Map.of("seed", "true")));
                provider.chunkStore().save(new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true")));
                graphAdapter.graphStore().saveEntity(new GraphStore.EntityRecord(
                    "entity-0",
                    "Seed",
                    "seed",
                    "Seed entity",
                    List.of(),
                    List.of("doc-0:0")
                ));

                provider.restore(new SnapshotStore.Snapshot(
                    List.of(new DocumentStore.DocumentRecord("doc-1", "Replacement", "body", Map.of())),
                    List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of())),
                    List.of(new GraphStore.EntityRecord("entity-1", "Replacement", "person", "entity", List.of(), List.of("doc-1:0"))),
                    List.of(),
                    Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.0d, 1.0d, 0.0d)))),
                    List.of(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSED, "replacement", null))
                ));

                // The restore path keeps taking full snapshots for rollback safety; only writeAtomically was scoped.
                assertThat(graphAdapter.captureSnapshotCount()).isEqualTo(1);
                assertThat(vectorAdapter.captureSnapshotCount()).isEqualTo(1);
                assertThat(provider.documentStore().list())
                    .containsExactly(new DocumentStore.DocumentRecord("doc-1", "Replacement", "body", Map.of()));
                assertThat(provider.chunkStore().list())
                    .containsExactly(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
                assertThat(graphAdapter.graphStore().loadEntity("entity-1")).isPresent();
                assertThat(graphAdapter.graphStore().loadEntity("entity-0")).isEmpty();
            }
        }
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    }

    private static PostgresStorageConfig newConfig() {
        var schema = "lightrag_" + UUID.randomUUID().toString().replace("-", "");
        return new PostgresStorageConfig(
            withCurrentSchema(POSTGRES.getJdbcUrl(), schema),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            schema,
            3,
            "rag_"
        );
    }

    private static String withCurrentSchema(String jdbcUrl, String schema) {
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return jdbcUrl + separator + "currentSchema=" + schema;
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

    private static DataSource blockingChunkSelectDataSource(
        DataSource delegate,
        PostgresStorageConfig config,
        CountDownLatch selectStarted,
        CountDownLatch allowSelectToFinish
    ) {
        return (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> {
                if ("getConnection".equals(method.getName()) && (args == null || args.length == 0)) {
                    return blockingConnection(
                        delegate.getConnection(),
                        config,
                        selectStarted,
                        allowSelectToFinish
                    );
                }
                if ("getConnection".equals(method.getName()) && args != null && args.length == 2) {
                    return blockingConnection(
                        delegate.getConnection((String) args[0], (String) args[1]),
                        config,
                        selectStarted,
                        allowSelectToFinish
                    );
                }
                return method.invoke(delegate, args);
            }
        );
    }

    private static Connection blockingConnection(
        Connection delegate,
        PostgresStorageConfig config,
        CountDownLatch selectStarted,
        CountDownLatch allowSelectToFinish
    ) {
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String sql) {
                    PreparedStatement statement = (PreparedStatement) method.invoke(delegate, args);
                    if (isChunkLoadSql(sql, config)) {
                        return blockingPreparedStatement(statement, selectStarted, allowSelectToFinish);
                    }
                    return statement;
                }
                return method.invoke(delegate, args);
            }
        );
    }

    private static PreparedStatement blockingPreparedStatement(
        PreparedStatement delegate,
        CountDownLatch selectStarted,
        CountDownLatch allowSelectToFinish
    ) {
        return (PreparedStatement) Proxy.newProxyInstance(
            PreparedStatement.class.getClassLoader(),
            new Class<?>[] {PreparedStatement.class},
            (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName()) && (args == null || args.length == 0)) {
                    selectStarted.countDown();
                    if (!allowSelectToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to finish blocked chunk SELECT");
                    }
                }
                return method.invoke(delegate, args);
            }
        );
    }

    private static boolean isChunkLoadSql(String sql, PostgresStorageConfig config) {
        return sql != null
            && sql.contains("FROM " + config.qualifiedTableName("chunks"))
            && sql.contains("WHERE workspace_id = ?")
            && sql.contains("AND id = ?");
    }

    private static boolean tryExclusiveAdvisoryLock(PostgresStorageConfig config) throws SQLException {
        try (var connection = DriverManager.getConnection(
            config.jdbcUrl(),
            config.username(),
            config.password()
        )) {
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, deriveLockKey(config));
                try (var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    boolean acquired = resultSet.getBoolean(1);
                    if (acquired) {
                        try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                            unlock.setLong(1, deriveLockKey(config));
                            unlock.executeQuery();
                        }
                    }
                    return acquired;
                }
            }
        }
    }

    private static long deriveLockKey(PostgresStorageConfig config) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((config.schema() + ":" + config.tablePrefix() + ":default").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(Arrays.copyOf(digest, Long.BYTES)).getLong();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
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

    private static void awaitQueuedOn(ReentrantReadWriteLock lock, Thread worker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThread(worker) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(lock.hasQueuedThread(worker)).isTrue();
    }

    private static void awaitActiveExclusiveCalls(RecordingStorageLockManager manager, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.activeExclusiveCalls() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(manager.activeExclusiveCalls()).isEqualTo(expected);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class RecordingGraphProjection implements PostgresMilvusNeo4jStorageProvider.GraphProjection {
        private final Map<String, GraphStore.EntityRecord> entities = new LinkedHashMap<>();
        private final Map<String, GraphStore.RelationRecord> relations = new LinkedHashMap<>();
        private final List<String> knowledgeGraphCalls = new java.util.ArrayList<>();
        private KnowledgeGraphView knowledgeGraphView = new KnowledgeGraphView(List.of(), List.of(), false);
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
        public void saveEntities(List<GraphStore.EntityRecord> records) {
            records.forEach(this::saveEntity);
        }

        @Override
        public void saveRelations(List<GraphStore.RelationRecord> records) {
            records.forEach(this::saveRelation);
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
                .filter(relation -> relation.srcId().equals(entityId) || relation.tgtId().equals(entityId))
                .toList();
        }

        @Override
        public Map<String, List<GraphStore.RelationRecord>> findRelations(List<String> entityIds) {
            return entityIds.stream().collect(java.util.stream.Collectors.toMap(
                entityId -> entityId,
                this::findRelations,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        }

        @Override
        public int deleteEntities(List<String> entityIds) {
            int deleted = 0;
            for (String entityId : entityIds) {
                if (entities.remove(entityId) != null) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public int deleteRelations(List<String> relationIds) {
            int deleted = 0;
            for (String relationId : relationIds) {
                if (relations.remove(relationId) != null) {
                    deleted++;
                }
            }
            return deleted;
        }

        @Override
        public KnowledgeGraphView getKnowledgeGraph(String nodeLabel, int maxDepth, int maxNodes) {
            knowledgeGraphCalls.add(nodeLabel + "/" + maxDepth + "/" + maxNodes);
            return knowledgeGraphView;
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

        void stubKnowledgeGraph(KnowledgeGraphView view) {
            this.knowledgeGraphView = Objects.requireNonNull(view, "view");
        }

        List<String> knowledgeGraphCalls() {
            return List.copyOf(knowledgeGraphCalls);
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
        public void deleteIds(String namespace, List<String> ids) {
            var target = namespace(namespace);
            ids.forEach(target::remove);
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
        private int captureSnapshotCount;
        private List<String> capturedEntityIds = List.of();
        private List<String> capturedRelationIds = List.of();

        @Override
        public GraphStore graphStore() {
            return projection;
        }

        @Override
        public GraphSnapshot captureSnapshot() {
            captureSnapshotCount++;
            return new GraphSnapshot(projection.allEntities(), projection.allRelations());
        }

        @Override
        public Optional<PreImage> capturePreImage(Collection<String> entityIds, Collection<String> relationIds) {
            capturedEntityIds = List.copyOf(entityIds);
            capturedRelationIds = List.copyOf(relationIds);
            return Optional.of(new ScopedPreImage(
                capturedEntityIds,
                capturedRelationIds,
                capturedEntityIds.stream().map(projection::loadEntity).flatMap(Optional::stream).toList(),
                capturedRelationIds.stream().map(projection::loadRelation).flatMap(Optional::stream).toList()
            ));
        }

        @Override
        public void restorePreImage(PreImage preImage) {
            var scoped = (ScopedPreImage) preImage;
            var presentEntityIds = scoped.entities().stream()
                .map(GraphStore.EntityRecord::id)
                .collect(java.util.stream.Collectors.toSet());
            var absentEntityIds = scoped.entityIds().stream()
                .filter(id -> !presentEntityIds.contains(id))
                .toList();
            if (!absentEntityIds.isEmpty()) {
                projection.deleteEntities(absentEntityIds);
            }
            projection.saveEntities(scoped.entities());
            var presentRelationIds = scoped.relations().stream()
                .map(GraphStore.RelationRecord::id)
                .collect(java.util.stream.Collectors.toSet());
            var absentRelationIds = scoped.relationIds().stream()
                .filter(id -> !presentRelationIds.contains(id))
                .toList();
            if (!absentRelationIds.isEmpty()) {
                projection.deleteRelations(absentRelationIds);
            }
            projection.saveRelations(scoped.relations());
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

        int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        List<String> capturedEntityIds() {
            return capturedEntityIds;
        }

        List<String> capturedRelationIds() {
            return capturedRelationIds;
        }
    }

    private record ScopedPreImage(
        List<String> entityIds,
        List<String> relationIds,
        List<GraphStore.EntityRecord> entities,
        List<GraphStore.RelationRecord> relations
    ) implements GraphStorageAdapter.PreImage {
    }

    private static final class RecordingVectorStorageAdapter implements VectorStorageAdapter {
        private final RecordingMilvusProjection projection = new RecordingMilvusProjection();
        private int applyCount;
        private int captureSnapshotCount;
        private Map<String, List<String>> capturedVectorIdsByNamespace = Map.of();

        @Override
        public VectorStore vectorStore() {
            return projection;
        }

        @Override
        public VectorSnapshot captureSnapshot() {
            captureSnapshotCount++;
            return new VectorSnapshot(Map.of(
                "chunks", projection.list("chunks"),
                "entities", projection.list("entities"),
                "relations", projection.list("relations")
            ));
        }

        @Override
        public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
            var requestedByNamespace = new LinkedHashMap<String, List<String>>();
            var recordsByNamespace = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
            for (var entry : idsByNamespace.entrySet()) {
                var ids = List.copyOf(entry.getValue());
                if (ids.isEmpty()) {
                    continue;
                }
                requestedByNamespace.put(entry.getKey(), ids);
                var requestedIds = new java.util.LinkedHashSet<>(ids);
                recordsByNamespace.put(entry.getKey(), projection.list(entry.getKey()).stream()
                    .filter(record -> requestedIds.contains(record.id()))
                    .toList());
            }
            capturedVectorIdsByNamespace = requestedByNamespace;
            return Optional.of(new ScopedVectorPreImage(requestedByNamespace, recordsByNamespace));
        }

        @Override
        public void restorePreImage(PreImage preImage) {
            var scoped = (ScopedVectorPreImage) preImage;
            for (var entry : scoped.recordsByNamespace().entrySet()) {
                var namespace = entry.getKey();
                var presentIds = entry.getValue().stream()
                    .map(VectorStore.VectorRecord::id)
                    .collect(java.util.stream.Collectors.toSet());
                var absentIds = scoped.idsByNamespace().getOrDefault(namespace, List.of()).stream()
                    .filter(id -> !presentIds.contains(id))
                    .toList();
                if (!absentIds.isEmpty()) {
                    projection.deleteIds(namespace, absentIds);
                }
                if (!entry.getValue().isEmpty()) {
                    projection.saveAll(namespace, entry.getValue());
                }
            }
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

        int captureSnapshotCount() {
            return captureSnapshotCount;
        }

        Map<String, List<String>> capturedVectorIdsByNamespace() {
            return capturedVectorIdsByNamespace;
        }
    }

    private record ScopedVectorPreImage(
        Map<String, List<String>> idsByNamespace,
        Map<String, List<VectorStore.VectorRecord>> recordsByNamespace
    ) implements VectorStorageAdapter.PreImage {
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

    private static final class ExclusiveStorageLockManager implements StorageLockManager {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicInteger exclusiveCalls = new AtomicInteger();
        private final Map<Thread, Integer> holders = new ConcurrentHashMap<>();
        private final AtomicInteger maxConcurrentThreads = new AtomicInteger();
        private final CountDownLatch supplierEntered = new CountDownLatch(1);

        @Override
        public <T> T withExclusiveLock(Supplier<T> supplier) {
            lock.lock();
            try {
                exclusiveCalls.incrementAndGet();
                holders.merge(Thread.currentThread(), 1, Integer::sum);
                maxConcurrentThreads.accumulateAndGet(holders.size(), Math::max);
                supplierEntered.countDown();
                return supplier.get();
            } finally {
                holders.computeIfPresent(Thread.currentThread(), (thread, depth) -> depth <= 1 ? null : depth - 1);
                lock.unlock();
            }
        }

        int exclusiveCalls() {
            return exclusiveCalls.get();
        }

        int maxConcurrentThreads() {
            return maxConcurrentThreads.get();
        }

        boolean awaitSupplierEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return supplierEntered.await(timeout, unit);
        }
    }

    private static final class RecordingStorageLockManager implements StorageLockManager {
        private final AtomicInteger exclusiveCalls = new AtomicInteger();
        private final AtomicInteger activeExclusiveCalls = new AtomicInteger();

        @Override
        public <T> T withExclusiveLock(Supplier<T> supplier) {
            exclusiveCalls.incrementAndGet();
            activeExclusiveCalls.incrementAndGet();
            try {
                return supplier.get();
            } finally {
                activeExclusiveCalls.decrementAndGet();
            }
        }

        int exclusiveCalls() {
            return exclusiveCalls.get();
        }

        int activeExclusiveCalls() {
            return activeExclusiveCalls.get();
        }
    }

    private static final class BlockingStorageLockManager implements StorageLockManager {
        private final AtomicBoolean blocking = new AtomicBoolean();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void startBlocking() {
            blocking.set(true);
        }

        @Override
        public <T> T withExclusiveLock(Supplier<T> supplier) {
            if (!blocking.get()) {
                return supplier.get();
            }
            entered.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the blocked storage lock");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release the blocked storage lock", interrupted);
            }
            return supplier.get();
        }

        boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
            return entered.await(timeout, unit);
        }

        void release() {
            released.countDown();
        }
    }
}
