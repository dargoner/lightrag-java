package io.github.lightrag.storage;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDocumentGraphStoresTest {
    @Test
    void inMemorySnapshotStorePersistsDocumentAndChunkSnapshots() {
        var provider = InMemoryStorageProvider.create();
        var snapshots = provider.documentGraphSnapshotStore();
        var now = Instant.now();

        snapshots.saveDocument(documentSnapshot("doc-1", now));
        snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "chunk-1", now)));

        assertThat(snapshots.loadDocument("doc-1")).isPresent();
        assertThat(snapshots.listChunks("doc-1")).hasSize(1);
    }

    @Test
    void snapshotStoreCopiesInputAndReturnsImmutableChunkList() {
        var snapshots = InMemoryStorageProvider.create().documentGraphSnapshotStore();
        var now = Instant.now();
        var chunks = new ArrayList<DocumentGraphSnapshotStore.ChunkGraphSnapshot>();
        chunks.add(chunkSnapshot("doc-1", "chunk-1", now));

        snapshots.saveChunks("doc-1", chunks);
        chunks.clear();

        var stored = snapshots.listChunks("doc-1");
        assertThat(stored).hasSize(1);
        assertThatThrownBy(() -> stored.add(chunkSnapshot("doc-1", "chunk-2", now)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotStoreDeleteRemovesDocumentAndChunks() {
        var snapshots = InMemoryStorageProvider.create().documentGraphSnapshotStore();
        var now = Instant.now();
        snapshots.saveDocument(documentSnapshot("doc-1", now));
        snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "chunk-1", now)));

        snapshots.delete("doc-1");

        assertThat(snapshots.loadDocument("doc-1")).isEmpty();
        assertThat(snapshots.listChunks("doc-1")).isEmpty();
    }

    @Test
    void snapshotStoreRejectsChunkDocumentIdMismatch() {
        var snapshots = InMemoryStorageProvider.create().documentGraphSnapshotStore();
        var now = Instant.now();

        assertThatThrownBy(() -> snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-2", "chunk-1", now))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("documentId");
    }

    @Test
    void snapshotStoreReplacesChunks() {
        var snapshots = InMemoryStorageProvider.create().documentGraphSnapshotStore();
        var now = Instant.now();

        snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "chunk-1", now)));
        snapshots.saveChunks("doc-1", List.of(chunkSnapshot("doc-1", "chunk-2", now)));

        assertThat(snapshots.listChunks("doc-1"))
            .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
            .containsExactly("chunk-2");
    }

    @Test
    void inMemoryJournalStorePersistsDocumentAndChunkJournals() {
        var provider = InMemoryStorageProvider.create();
        var journals = provider.documentGraphJournalStore();
        var now = Instant.now();

        journals.appendDocument(documentJournal("doc-1", 1, now));
        journals.appendChunks("doc-1", List.of(chunkJournal("doc-1", "chunk-1", 1, now)));

        assertThat(journals.listDocumentJournals("doc-1")).hasSize(1);
        assertThat(journals.listChunkJournals("doc-1")).hasSize(1);
    }

    @Test
    void journalStoreKeepsLatestDocumentAndUpsertsChunkRows() {
        var journals = InMemoryStorageProvider.create().documentGraphJournalStore();
        var now = Instant.now();
        var first = documentJournal("doc-1", 1, now.minusSeconds(2));
        var second = documentJournal("doc-1", 2, now.minusSeconds(1));
        journals.appendDocument(first);
        journals.appendDocument(second);

        journals.appendChunks("doc-1", List.of(
            chunkJournal("doc-1", "chunk-1", 1, now.minusSeconds(2)),
            chunkJournal("doc-1", "chunk-2", 1, now.minusSeconds(1))
        ));
        journals.appendChunks("doc-1", List.of(chunkJournal("doc-1", "chunk-1", 2, now)));

        var documentList = journals.listDocumentJournals("doc-1");
        var chunkList = journals.listChunkJournals("doc-1");
        assertThat(documentList).containsExactly(second);
        assertThat(chunkList).hasSize(2);
        var chunkById = chunkList.stream().collect(Collectors.toMap(
            DocumentGraphJournalStore.ChunkGraphJournal::chunkId,
            Function.identity()
        ));
        assertThat(chunkById.get("chunk-1").snapshotVersion()).isEqualTo(2);
        assertThat(chunkById.get("chunk-2").snapshotVersion()).isEqualTo(1);
        assertThatThrownBy(() -> documentList.add(documentJournal("doc-1", 3, now)))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> chunkList.add(chunkJournal("doc-1", "chunk-4", 3, now)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void journalStoreDeleteRemovesDocumentAndChunks() {
        var journals = InMemoryStorageProvider.create().documentGraphJournalStore();
        var now = Instant.now();
        journals.appendDocument(documentJournal("doc-1", 1, now));
        journals.appendChunks("doc-1", List.of(chunkJournal("doc-1", "chunk-1", 1, now)));

        journals.delete("doc-1");

        assertThat(journals.listDocumentJournals("doc-1")).isEmpty();
        assertThat(journals.listChunkJournals("doc-1")).isEmpty();
    }

    @Test
    void journalStoreRejectsChunkDocumentIdMismatch() {
        var journals = InMemoryStorageProvider.create().documentGraphJournalStore();
        var now = Instant.now();

        assertThatThrownBy(() -> journals.appendChunks("doc-1", List.of(chunkJournal("doc-2", "chunk-1", 1, now))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("documentId");
    }

    private static DocumentGraphSnapshotStore.DocumentGraphSnapshot documentSnapshot(String documentId, Instant now) {
        return new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            1,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            2,
            now,
            now,
            null
        );
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot(String documentId, String chunkId, Instant now) {
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            documentId,
            chunkId,
            0,
            "hash-" + chunkId,
            ChunkExtractStatus.SUCCEEDED,
            List.of(new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                "entity-a",
                "person",
                "desc",
                List.of("alias-a")
            )),
            List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                "entity-a",
                "entity-b",
                "works_with",
                "rel-desc",
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
            3,
            2,
            3,
            2,
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
            List.of("entity-a"),
            List.of("relation-a"),
            List.of("entity-a"),
            List.of("relation-a"),
            FailureStage.ENTITY_MATERIALIZATION,
            now,
            null
        );
    }
}
