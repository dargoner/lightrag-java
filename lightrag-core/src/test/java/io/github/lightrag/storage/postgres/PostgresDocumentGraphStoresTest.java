package io.github.lightrag.storage.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresDocumentGraphStoresTest {
    @Test
    void roundTripsDocumentAndChunkSnapshots() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container, "workspace-a");
        ) {
            var documentSnapshot = new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
                "doc-1",
                2,
                SnapshotStatus.PARTIAL,
                SnapshotSource.PRIMARY_EXTRACTION,
                2,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:05:00Z"),
                "document warning"
            );
            var chunks = List.of(
                new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                    "doc-1",
                    "chunk-2",
                    1,
                    "hash-2",
                    ChunkExtractStatus.FAILED,
                    List.of(),
                    List.of(),
                    Instant.parse("2026-01-01T00:07:00Z"),
                    "chunk-2 warning"
                ),
                new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                    "doc-1",
                    "chunk-1",
                    0,
                    "hash-1",
                    ChunkExtractStatus.SUCCEEDED,
                    List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord("Alice", "person", "Researcher", List.of("A"))),
                    List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord("Alice", "Bob", "knows", "Alice knows Bob", 0.7d)),
                    Instant.parse("2026-01-01T00:06:00Z"),
                    null
                )
            );

            resources.snapshotStore().saveDocument(documentSnapshot);
            resources.snapshotStore().saveChunks("doc-1", chunks);

            assertThat(resources.snapshotStore().loadDocument("doc-1")).contains(documentSnapshot);
            assertThat(resources.snapshotStore().listChunks("doc-1")).containsExactly(
                chunks.get(1),
                chunks.get(0)
            );
        }
    }

    @Test
    void roundTripsDocumentAndChunkJournals() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container, "workspace-a");
        ) {
            var documentJournal = new DocumentGraphJournalStore.DocumentGraphJournal(
                "doc-1",
                3,
                GraphMaterializationStatus.PARTIAL,
                GraphMaterializationMode.REPAIR,
                5,
                4,
                3,
                2,
                FailureStage.RELATION_MATERIALIZATION,
                Instant.parse("2026-01-02T00:00:00Z"),
                Instant.parse("2026-01-02T00:05:00Z"),
                "journal warning"
            );
            var chunkJournals = List.of(
                new DocumentGraphJournalStore.ChunkGraphJournal(
                    "doc-1",
                    "chunk-b",
                    3,
                    ChunkMergeStatus.FAILED,
                    ChunkGraphStatus.PARTIAL,
                    List.of("entity:alice"),
                    List.of("relation:alice->bob"),
                    List.of("entity:alice"),
                    List.of(),
                    FailureStage.RELATION_MATERIALIZATION,
                    Instant.parse("2026-01-02T00:06:00Z"),
                    "chunk-b warning"
                ),
                new DocumentGraphJournalStore.ChunkGraphJournal(
                    "doc-1",
                    "chunk-a",
                    3,
                    ChunkMergeStatus.SUCCEEDED,
                    ChunkGraphStatus.MATERIALIZED,
                    List.of("entity:alice"),
                    List.of("relation:alice->bob"),
                    List.of("entity:alice"),
                    List.of("relation:alice->bob"),
                    null,
                    Instant.parse("2026-01-02T00:05:30Z"),
                    null
                )
            );

            resources.journalStore().appendDocument(documentJournal);
            resources.journalStore().appendChunks("doc-1", chunkJournals);

            assertThat(resources.journalStore().listDocumentJournals("doc-1")).containsExactly(documentJournal);
            assertThat(resources.journalStore().listChunkJournals("doc-1")).containsExactly(
                chunkJournals.get(1),
                chunkJournals.get(0)
            );
        }
    }

    @Test
    void isolatesDataByWorkspace() {
        try (
            var container = newPostgresContainer();
            var workspaceA = newStoreResources(container, "workspace-a");
            var workspaceB = newStoreResources(container, "workspace-b");
        ) {
            var snapshot = new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
                "doc-1",
                1,
                SnapshotStatus.READY,
                SnapshotSource.PRIMARY_EXTRACTION,
                0,
                Instant.parse("2026-01-03T00:00:00Z"),
                Instant.parse("2026-01-03T00:00:01Z"),
                null
            );
            var journal = new DocumentGraphJournalStore.DocumentGraphJournal(
                "doc-1",
                1,
                GraphMaterializationStatus.MERGED,
                GraphMaterializationMode.AUTO,
                1,
                1,
                1,
                1,
                null,
                Instant.parse("2026-01-03T00:00:02Z"),
                Instant.parse("2026-01-03T00:00:03Z"),
                null
            );

            workspaceA.snapshotStore().saveDocument(snapshot);
            workspaceA.journalStore().appendDocument(journal);

            assertThat(workspaceA.snapshotStore().loadDocument("doc-1")).contains(snapshot);
            assertThat(workspaceA.journalStore().listDocumentJournals("doc-1")).containsExactly(journal);
            assertThat(workspaceB.snapshotStore().loadDocument("doc-1")).isEmpty();
            assertThat(workspaceB.journalStore().listDocumentJournals("doc-1")).isEmpty();
        }
    }

    @Test
    void saveChunksReplacesCurrentChunkSnapshotSetAndCleansRemovedRows() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container, "workspace-a");
        ) {
            resources.snapshotStore().saveChunks("doc-1", List.of(
                chunkSnapshot("doc-1", "chunk-1", 0, "hash-1"),
                chunkSnapshot("doc-1", "chunk-2", 1, "hash-2")
            ));

            resources.snapshotStore().saveChunks("doc-1", List.of(
                chunkSnapshot("doc-1", "chunk-2", 0, "hash-2-updated")
            ));

            assertThat(resources.snapshotStore().listChunks("doc-1")).containsExactly(
                chunkSnapshot("doc-1", "chunk-2", 0, "hash-2-updated")
            );
        }
    }

    @Test
    void appendChunksUpsertsPerChunkAndKeepsLatestRow() {
        try (
            var container = newPostgresContainer();
            var resources = newStoreResources(container, "workspace-a");
        ) {
            resources.journalStore().appendChunks("doc-1", List.of(
                chunkJournal("doc-1", "chunk-1", 1, ChunkMergeStatus.RUNNING, ChunkGraphStatus.NOT_MATERIALIZED, "first")
            ));

            resources.journalStore().appendChunks("doc-1", List.of(
                chunkJournal("doc-1", "chunk-1", 2, ChunkMergeStatus.SUCCEEDED, ChunkGraphStatus.MATERIALIZED, "latest"),
                chunkJournal("doc-1", "chunk-2", 1, ChunkMergeStatus.FAILED, ChunkGraphStatus.FAILED, "other")
            ));

            assertThat(resources.journalStore().listChunkJournals("doc-1")).containsExactly(
                chunkJournal("doc-1", "chunk-1", 2, ChunkMergeStatus.SUCCEEDED, ChunkGraphStatus.MATERIALIZED, "latest"),
                chunkJournal("doc-1", "chunk-2", 1, ChunkMergeStatus.FAILED, ChunkGraphStatus.FAILED, "other")
            );
        }
    }

    @Test
    void bootstrapRejectsGraphStateTablesMissingWorkspaceId() throws Exception {
        try (var container = newPostgresContainer()) {
            container.start();
            var config = new PostgresStorageConfig(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword(),
                "lightrag",
                3,
                "rag_"
            );

            try (
                var connection = DriverManager.getConnection(
                    container.getJdbcUrl(),
                    container.getUsername(),
                    container.getPassword()
                );
                var statement = connection.createStatement()
            ) {
                statement.execute("CREATE SCHEMA IF NOT EXISTS " + config.schemaName());
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS %s (
                        document_id TEXT NOT NULL PRIMARY KEY,
                        version INTEGER NOT NULL
                    )
                    """.formatted(config.qualifiedTableName("document_graph_snapshots"))
                );
            }

            try (var dataSource = newDataSource(config)) {
                assertThatThrownBy(() -> new PostgresSchemaManager(dataSource, config).bootstrap())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("workspace_id")
                    .hasMessageContaining("document_graph_snapshots");
            }
        }
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot(
        String documentId,
        String chunkId,
        int chunkOrder,
        String hash
    ) {
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            documentId,
            chunkId,
            chunkOrder,
            hash,
            ChunkExtractStatus.SUCCEEDED,
            List.of(),
            List.of(),
            Instant.parse("2026-01-01T01:00:00Z"),
            null
        );
    }

    private static DocumentGraphJournalStore.ChunkGraphJournal chunkJournal(
        String documentId,
        String chunkId,
        int version,
        ChunkMergeStatus mergeStatus,
        ChunkGraphStatus graphStatus,
        String marker
    ) {
        return new DocumentGraphJournalStore.ChunkGraphJournal(
            documentId,
            chunkId,
            version,
            mergeStatus,
            graphStatus,
            List.of("expected-entity-" + marker),
            List.of("expected-relation-" + marker),
            List.of("materialized-entity-" + marker),
            List.of("materialized-relation-" + marker),
            null,
            Instant.parse("2026-01-04T00:00:00Z"),
            marker
        );
    }

    private static PostgreSQLContainer<?> newPostgresContainer() {
        var image = DockerImageName.parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");
        return new PostgreSQLContainer<>(image);
    }

    private static StoreResources newStoreResources(PostgreSQLContainer<?> container, String workspaceId) {
        if (!container.isRunning()) {
            container.start();
        }
        var config = new PostgresStorageConfig(
            container.getJdbcUrl(),
            container.getUsername(),
            container.getPassword(),
            "lightrag",
            3,
            "rag_"
        );
        var dataSource = newDataSource(config);
        new PostgresSchemaManager(dataSource, config).bootstrap();
        return new StoreResources(
            dataSource,
            new PostgresDocumentGraphSnapshotStore(dataSource, config, workspaceId),
            new PostgresDocumentGraphJournalStore(dataSource, config, workspaceId)
        );
    }

    private static HikariDataSource newDataSource(PostgresStorageConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.jdbcUrl());
        hikariConfig.setUsername(config.username());
        hikariConfig.setPassword(config.password());
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        return new HikariDataSource(hikariConfig);
    }

    private record StoreResources(
        HikariDataSource dataSource,
        PostgresDocumentGraphSnapshotStore snapshotStore,
        PostgresDocumentGraphJournalStore journalStore
    ) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
