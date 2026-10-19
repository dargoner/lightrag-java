package io.github.lightrag.persistence;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.exception.StorageException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.lightrag.support.RelationIds.relationId;

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

    @Test
    void saveWritesCurrentSchemaVersionTwo() throws Exception {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");

        store.save(snapshotPath, sampleSnapshot());

        assertThat(SnapshotManifest.CURRENT_SCHEMA_VERSION).isEqualTo(2);
        assertThat(store.readManifest(snapshotPath).schemaVersion()).isEqualTo(2);
    }

    @Test
    void loadDefaultsMissingEntityFilePathToEmptyForLegacyPayloads() throws Exception {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        Files.writeString(snapshotPath, """
            {"schemaVersion":1,"createdAt":"2026-04-12T08:00:00Z","payloadFile":"repository.snapshot.payload.json"}
            """);
        Files.writeString(tempDir.resolve("repository.snapshot.payload.json"), """
            {
              "documents": [],
              "chunks": [],
              "entities": [
                {"id":"alice","name":"Alice","type":"person","description":"Researcher",
                 "aliases":["Al"],"sourceChunkIds":["doc-1:0"]}
              ],
              "relations": [],
              "vectors": {}
            }
            """);

        var loaded = store.load(snapshotPath);

        assertThat(loaded.entities()).singleElement()
            .extracting(GraphStore.EntityRecord::filePath)
            .isEqualTo("");
    }

    @Test
    void loadVersionOneIgnoresAttributesAndReturnsEmptyMaps() throws Exception {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        Files.writeString(snapshotPath, """
            {"schemaVersion":1,"createdAt":"2026-04-12T08:00:00Z","payloadFile":"repository.snapshot.payload.json"}
            """);
        Files.writeString(tempDir.resolve("repository.snapshot.payload.json"), """
            {
              "documents": [],
              "chunks": [],
              "entities": [
                {"id":"alice","name":"Alice","type":"person","description":"Researcher",
                 "aliases":["Al"],"sourceChunkIds":["doc-1:0"],"filePath":"","attributes":{"Age":33}}
              ],
              "relations": [
                {"relationId":"alice|works_with|bob","srcId":"alice","tgtId":"bob","keywords":"works_with",
                 "description":"Collaboration","weight":0.5,"sourceId":"doc-1:0","filePath":"",
                 "attributes":{"since":2020}}
              ],
              "vectors": {}
            }
            """);

        var loaded = store.load(snapshotPath);

        assertThat(loaded.entities()).singleElement()
            .satisfies(entity -> assertThat(entity.attributes()).isEmpty());
        assertThat(loaded.relations()).singleElement()
            .satisfies(relation -> assertThat(relation.attributes()).isEmpty());
    }

    @Test
    void versionTwoRoundTripsNonEmptyAttributes() {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        var snapshot = snapshotWithAttributes();

        store.save(snapshotPath, snapshot);

        var loaded = store.load(snapshotPath);
        assertThat(loaded).isEqualTo(snapshot);
        assertThat(loaded.entities()).singleElement()
            .satisfies(entity -> assertThat(entity.attributes())
                .containsEntry("Age", 33L)
                .containsEntry("tags", List.of("researcher", "alice")));
        assertThat(loaded.relations()).singleElement()
            .satisfies(relation -> assertThat(relation.attributes())
                .containsEntry("since", 2020L));
        assertThat(loaded.chunkGraphSnapshots()).singleElement()
            .satisfies(chunk -> {
                assertThat(chunk.entities()).singleElement()
                    .satisfies(entity -> assertThat(entity.attributes()).containsEntry("Age", 33L));
                assertThat(chunk.relations()).singleElement()
                    .satisfies(relation -> assertThat(relation.attributes()).containsEntry("since", 2020L));
            });
    }

    @Test
    void rejectsManifestSchemaVersionAboveCurrent() {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");
        store.save(snapshotPath, sampleSnapshot());

        assertThatThrownBy(() -> {
            Files.writeString(snapshotPath, """
                {"schemaVersion":3,"createdAt":"2026-04-12T08:00:00Z",
                 "payloadFile":"repository.snapshot.payload.json"}
                """);
            store.load(snapshotPath);
        })
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("Unsupported snapshot schema version 3")
            .hasMessageContaining("Upgrade the SDK");
    }

    @Test
    void failsLoudlyOnMalformedOrInvalidManifest() {
        var store = new FileSnapshotStore();
        var snapshotPath = tempDir.resolve("repository.snapshot.json");

        assertThatThrownBy(() -> {
            Files.writeString(snapshotPath, "{\"schemaVersion\":");
            store.load(snapshotPath);
        })
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("Failed to read snapshot manifest");

        assertThatThrownBy(() -> {
            Files.writeString(snapshotPath, """
                {"schemaVersion":0,"createdAt":"2026-04-12T08:00:00Z",
                 "payloadFile":"repository.snapshot.payload.json"}
                """);
            store.load(snapshotPath);
        })
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("Failed to read snapshot manifest");
    }

    private static SnapshotStore.Snapshot snapshotWithAttributes() {
        var now = Instant.parse("2026-04-12T08:00:00Z");
        return new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test"))),
            List.of(new GraphStore.EntityRecord(
                "alice",
                "Alice",
                "person",
                "Researcher",
                List.of("Al"),
                List.of("doc-1:0"),
                "/a.md",
                Map.of("Age", 33L, "tags", List.of("researcher", "alice"))
            )),
            List.of(new GraphStore.RelationRecord(
                relationId("alice", "bob"),
                "alice",
                "bob",
                "works_with",
                "Collaboration",
                0.8d,
                "doc-1:0",
                "",
                Map.of("since", 2020L)
            )),
            Map.of("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(1.0d, 0.0d)))),
            List.of(),
            List.of(),
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
                    List.of("Al"),
                    Map.of("Age", 33L)
                )),
                List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                    "Alice",
                    "Bob",
                    "works_with",
                    "Collaboration",
                    0.8d,
                    Map.of("since", 2020L)
                )),
                now,
                null
            )),
            List.of(),
            List.of()
        );
    }

    private static SnapshotStore.Snapshot sampleSnapshot() {
        var now = Instant.parse("2026-04-12T08:00:00Z");
        return new SnapshotStore.Snapshot(
            List.of(new DocumentStore.DocumentRecord("doc-1", "Title", "Body", Map.of("source", "test"))),
            List.of(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "Body", 4, 0, Map.of("source", "test"))),
            List.of(new GraphStore.EntityRecord(
                "alice",
                "Alice",
                "person",
                "Researcher",
                List.of("Al"),
                List.of("doc-1:0"),
                "/a.md<SEP>/b.md"
            )),
            List.of(new GraphStore.RelationRecord(
                relationId("alice", "bob"),
                "alice",
                "bob",
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
