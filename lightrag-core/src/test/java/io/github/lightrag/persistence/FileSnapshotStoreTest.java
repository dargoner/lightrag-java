package io.github.lightrag.persistence;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileSnapshotStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void saveWritesManifestAndRepositoryData() throws Exception {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        var snapshot = sampleSnapshot();

        store.save(snapshotPath, snapshot);

        assertThat(Files.exists(snapshotPath)).isTrue();
        var manifest = store.readManifest(snapshotPath);
        var payloadPath = snapshotPath.resolveSibling(manifest.payloadFile());
        assertThat(manifest.schemaVersion()).isEqualTo(SnapshotManifest.CURRENT_SCHEMA_VERSION);
        assertThat(manifest.createdAt()).isNotBlank();
        assertThat(Files.exists(payloadPath)).isTrue();
    }

    @Test
    void loadRestoresDocumentsChunksGraphAndVectors() {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        var snapshot = sampleSnapshot();

        store.save(snapshotPath, snapshot);

        assertThat(store.load(snapshotPath)).isEqualTo(snapshot);
    }

    @Test
    void saveUsesAtomicReplaceSemantics() throws Exception {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        var first = sampleSnapshot();
        var second = new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-2", "Title 2", "Body 2", Map.of())),
            List.of(new ChunkStore.ChunkRecord("doc-2:0", "doc-2", "Body 2", 5, 0, Map.of())),
            List.of(),
            List.of(),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-2:0", List.of(0.2d, 0.8d))))
        );

        store.save(snapshotPath, first);
        store.save(snapshotPath, second);

        assertThat(store.load(snapshotPath)).isEqualTo(second);
        try (var files = Files.list(tempDir)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                .allSatisfy(name -> assertThat(name).doesNotContain(".tmp"));
        }
    }

    private static SnapshotStore.Snapshot sampleSnapshot() {
        var now = Instant.parse("2026-04-12T08:00:00Z");
        return new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test"))),
            List.of(new GraphStore.EntityRecord(
                "entity:alice",
                "Alice",
                "person",
                "Researcher",
                List.of("Al"),
                List.of("doc-1:0")
            )),
            List.of(new GraphStore.RelationRecord(
                "relation:entity:alice|works_with|entity:bob",
                "entity:alice",
                "entity:bob",
                "works_with",
                "Collaboration",
                0.8d,
                List.of("doc-1:0")
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d)))),
            List.of(),
            List.of(new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
                "doc-1",
                2,
                SnapshotStatus.READY,
                SnapshotSource.PRIMARY_EXTRACTION,
                1,
                now.minusSeconds(30),
                now,
                null
            )),
            List.of(new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                "doc-1",
                "doc-1:0",
                0,
                "hash-doc-1:0",
                ChunkExtractStatus.SUCCEEDED,
                List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                    "Alice",
                    "person",
                    "Researcher",
                    List.of("Al")
                )),
                List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                    "Alice",
                    "Bob",
                    "works_with",
                    "Collaboration",
                    0.8d
                )),
                now,
                null
            )),
            List.of(new DocumentGraphJournalStore.DocumentGraphJournal(
                "doc-1",
                2,
                GraphMaterializationStatus.MERGED,
                GraphMaterializationMode.AUTO,
                1,
                1,
                1,
                1,
                FailureStage.FINALIZING,
                now.minusSeconds(20),
                now,
                null
            )),
            List.of(new DocumentGraphJournalStore.ChunkGraphJournal(
                "doc-1",
                "doc-1:0",
                2,
                ChunkMergeStatus.SUCCEEDED,
                ChunkGraphStatus.MATERIALIZED,
                List.of("alice"),
                List.of("alice|works_with|bob"),
                List.of("alice"),
                List.of("alice|works_with|bob"),
                FailureStage.RELATION_MATERIALIZATION,
                now,
                null
            ))
        );
    }
}
