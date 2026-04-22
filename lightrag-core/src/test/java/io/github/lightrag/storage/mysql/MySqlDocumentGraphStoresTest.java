package io.github.lightrag.storage.mysql;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MySqlDocumentGraphStoresTest {
    @Container
    private static final MySQLContainer<?> MYSQL = newMySqlContainer();

    @Test
    void snapshotStoreRoundTripDocumentAndChunkSnapshots() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var document = documentSnapshot("doc-1", 2, now);
        var chunk = chunkSnapshot("doc-1", "chunk-1", 0, now);
        var config = newConfig();
        try (
            var resources = newStoreResources(config, "default")
        ) {
            resources.snapshotStore().saveDocument(document);
            resources.snapshotStore().saveChunks("doc-1", List.of(chunk));

            assertThat(resources.snapshotStore().loadDocument("doc-1")).contains(document);
            assertThat(resources.snapshotStore().listChunks("doc-1")).containsExactly(chunk);
        }
    }

    @Test
    void journalStoreRoundTripDocumentAndChunkJournals() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var document = documentJournal("doc-1", 3, now);
        var chunk = chunkJournal("doc-1", "chunk-1", 3, now);
        var config = newConfig();
        try (
            var resources = newStoreResources(config, "default")
        ) {
            resources.journalStore().appendDocument(document);
            resources.journalStore().appendChunks("doc-1", List.of(chunk));

            assertThat(resources.journalStore().listDocumentJournals("doc-1")).containsExactly(document);
            assertThat(resources.journalStore().listChunkJournals("doc-1")).containsExactly(chunk);
        }
    }

    @Test
    void storesIsolateRowsByWorkspace() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var config = newConfig();
        try (
            var alpha = newStoreResources(config, "alpha");
            var beta = newStoreResources(config, "beta")
        ) {
            var alphaDocument = documentSnapshot("doc-1", 1, now.minusSeconds(2));
            var betaDocument = documentSnapshot("doc-1", 4, now.minusSeconds(1));
            var alphaChunk = chunkSnapshot("doc-1", "chunk-1", 0, now.minusSeconds(2));
            var betaChunk = chunkSnapshot("doc-1", "chunk-1", 2, now.minusSeconds(1));
            var alphaJournal = documentJournal("doc-1", 1, now.minusSeconds(2));
            var betaJournal = documentJournal("doc-1", 4, now.minusSeconds(1));
            var alphaChunkJournal = chunkJournal("doc-1", "chunk-1", 1, now.minusSeconds(2));
            var betaChunkJournal = chunkJournal("doc-1", "chunk-1", 4, now.minusSeconds(1));

            alpha.snapshotStore().saveDocument(alphaDocument);
            alpha.snapshotStore().saveChunks("doc-1", List.of(alphaChunk));
            alpha.journalStore().appendDocument(alphaJournal);
            alpha.journalStore().appendChunks("doc-1", List.of(alphaChunkJournal));

            beta.snapshotStore().saveDocument(betaDocument);
            beta.snapshotStore().saveChunks("doc-1", List.of(betaChunk));
            beta.journalStore().appendDocument(betaJournal);
            beta.journalStore().appendChunks("doc-1", List.of(betaChunkJournal));

            assertThat(alpha.snapshotStore().loadDocument("doc-1")).contains(alphaDocument);
            assertThat(beta.snapshotStore().loadDocument("doc-1")).contains(betaDocument);
            assertThat(alpha.snapshotStore().listChunks("doc-1")).containsExactly(alphaChunk);
            assertThat(beta.snapshotStore().listChunks("doc-1")).containsExactly(betaChunk);
            assertThat(alpha.journalStore().listDocumentJournals("doc-1")).containsExactly(alphaJournal);
            assertThat(beta.journalStore().listDocumentJournals("doc-1")).containsExactly(betaJournal);
            assertThat(alpha.journalStore().listChunkJournals("doc-1")).containsExactly(alphaChunkJournal);
            assertThat(beta.journalStore().listChunkJournals("doc-1")).containsExactly(betaChunkJournal);
        }
    }

    @Test
    void saveChunksReplacesSetAndRemovesRowsNotInLatestInput() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var config = newConfig();
        try (
            var resources = newStoreResources(config, "default")
        ) {
            resources.snapshotStore().saveChunks("doc-1", List.of(
                chunkSnapshot("doc-1", "chunk-1", 0, now.minusSeconds(2)),
                chunkSnapshot("doc-1", "chunk-2", 1, now.minusSeconds(2))
            ));
            resources.snapshotStore().saveChunks("doc-1", List.of(
                chunkSnapshot("doc-1", "chunk-2", 4, now.minusSeconds(1)),
                chunkSnapshot("doc-1", "chunk-3", 5, now)
            ));

            assertThat(resources.snapshotStore().listChunks("doc-1"))
                .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
                .containsExactly("chunk-2", "chunk-3");
            assertThat(resources.snapshotStore().listChunks("doc-1"))
                .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkOrder)
                .containsExactly(4, 5);
        }
    }

    @Test
    void appendChunksUpsertsPerChunkAndKeepsStableOrdering() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var config = newConfig();
        try (
            var resources = newStoreResources(config, "default")
        ) {
            resources.journalStore().appendChunks("doc-1", List.of(
                chunkJournal("doc-1", "chunk-b", 1, now.minusSeconds(2)),
                chunkJournal("doc-1", "chunk-a", 1, now.minusSeconds(2))
            ));
            resources.journalStore().appendChunks("doc-1", List.of(
                chunkJournal("doc-1", "chunk-a", 4, now.minusSeconds(1)),
                chunkJournal("doc-1", "chunk-c", 5, now)
            ));

            var journals = resources.journalStore().listChunkJournals("doc-1");
            assertThat(journals)
                .extracting(DocumentGraphJournalStore.ChunkGraphJournal::chunkId)
                .containsExactly("chunk-a", "chunk-b", "chunk-c");
            assertThat(journals)
                .extracting(DocumentGraphJournalStore.ChunkGraphJournal::snapshotVersion)
                .containsExactly(4, 1, 5);
        }
    }

    @Test
    void bootstrapAppliesMissingGraphStateMigrationWhenSchemaVersionIsOlderThanLatest() throws Exception {
        var config = newConfig();

        try (var dataSource = newDataSource(config)) {
            new MySqlSchemaManager(dataSource, config).bootstrap();
        }

        try (
            var connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
            var statement = connection.createStatement()
        ) {
            statement.execute("DROP TABLE " + config.qualifiedTableName("chunk_graph_journals"));
            statement.execute("DROP TABLE " + config.qualifiedTableName("document_graph_journals"));
            statement.execute("DROP TABLE " + config.qualifiedTableName("chunk_graph_snapshots"));
            statement.execute("DROP TABLE " + config.qualifiedTableName("document_graph_snapshots"));
            statement.executeUpdate(
                """
                UPDATE %s
                SET version = 2
                WHERE schema_key = 'storage'
                """.formatted(config.qualifiedTableName("schema_version"))
            );
        }

        try (var dataSource = newDataSource(config)) {
            new MySqlSchemaManager(dataSource, config).bootstrap();
            var snapshotStore = new MySqlDocumentGraphSnapshotStore(dataSource, config, "default");
            var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            var snapshot = documentSnapshot("doc-migrated", 1, now);

            snapshotStore.saveDocument(snapshot);

            assertThat(snapshotStore.loadDocument("doc-migrated")).contains(snapshot);
        }
    }

    private static MySQLContainer<?> newMySqlContainer() {
        return new MySQLContainer<>(DockerImageName.parse("mysql:8.4"));
    }

    private static StoreResources newStoreResources(MySqlStorageConfig config, String workspaceId) {
        var dataSource = newDataSource(config);
        new MySqlSchemaManager(dataSource, config).bootstrap();
        return new StoreResources(
            dataSource,
            new MySqlDocumentGraphSnapshotStore(dataSource, config, workspaceId),
            new MySqlDocumentGraphJournalStore(dataSource, config, workspaceId)
        );
    }

    private static MySqlStorageConfig newConfig() {
        return new MySqlStorageConfig(
            MYSQL.getJdbcUrl(),
            MYSQL.getUsername(),
            MYSQL.getPassword(),
            "rag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_"
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

    private static DocumentGraphSnapshotStore.DocumentGraphSnapshot documentSnapshot(
        String documentId,
        int version,
        Instant now
    ) {
        return new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            version,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            2,
            now,
            now,
            null
        );
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot(
        String documentId,
        String chunkId,
        int chunkOrder,
        Instant now
    ) {
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            documentId,
            chunkId,
            chunkOrder,
            "hash-" + chunkId + "-" + chunkOrder,
            ChunkExtractStatus.SUCCEEDED,
            List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                "entity-" + chunkId,
                "person",
                "entity-desc-" + chunkId,
                List.of("alias-" + chunkId)
            )),
            List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                "entity-" + chunkId,
                "entity-target-" + chunkId,
                "works_with",
                "relation-desc-" + chunkId,
                1.0d
            )),
            now,
            null
        );
    }

    private static DocumentGraphJournalStore.DocumentGraphJournal documentJournal(String documentId, int version, Instant now) {
        return new DocumentGraphJournalStore.DocumentGraphJournal(
            documentId,
            version,
            GraphMaterializationStatus.MERGED,
            GraphMaterializationMode.AUTO,
            10,
            8,
            9,
            7,
            FailureStage.FINALIZING,
            now,
            now,
            null
        );
    }

    private static DocumentGraphJournalStore.ChunkGraphJournal chunkJournal(
        String documentId,
        String chunkId,
        int version,
        Instant now
    ) {
        return new DocumentGraphJournalStore.ChunkGraphJournal(
            documentId,
            chunkId,
            version,
            ChunkMergeStatus.SUCCEEDED,
            ChunkGraphStatus.MATERIALIZED,
            List.of("expected-entity-" + chunkId),
            List.of("expected-relation-" + chunkId),
            List.of("materialized-entity-" + chunkId),
            List.of("materialized-relation-" + chunkId),
            FailureStage.ENTITY_MATERIALIZATION,
            now,
            null
        );
    }

    private record StoreResources(
        HikariDataSource dataSource,
        MySqlDocumentGraphSnapshotStore snapshotStore,
        MySqlDocumentGraphJournalStore journalStore
    ) implements AutoCloseable {
        @Override
        public void close() {
            dataSource.close();
        }
    }
}
