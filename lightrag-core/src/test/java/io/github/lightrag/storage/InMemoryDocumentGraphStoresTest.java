package io.github.lightrag.storage;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDocumentGraphStoresTest {
    @Test
    void inMemorySnapshotStorePersistsDocumentAndChunkSnapshots() {
        var provider = InMemoryStorageProvider.create();
        var snapshots = provider.documentGraphSnapshotStore();
        var now = Instant.now();

        snapshots.saveDocument(new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            "doc-1",
            1,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            2,
            now,
            now,
            null
        ));
        snapshots.saveChunks("doc-1", List.of(
            new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                "doc-1",
                "chunk-1",
                0,
                "hash-1",
                ChunkExtractStatus.SUCCEEDED,
                List.of(),
                List.of(),
                now,
                null
            )
        ));

        assertThat(snapshots.loadDocument("doc-1")).isPresent();
        assertThat(snapshots.listChunks("doc-1")).hasSize(1);
    }

    @Test
    void inMemoryJournalStorePersistsDocumentAndChunkJournals() {
        var provider = InMemoryStorageProvider.create();
        var journals = provider.documentGraphJournalStore();
        var now = Instant.now();

        journals.appendDocument(new DocumentGraphJournalStore.DocumentGraphJournal(
            "doc-1",
            1,
            GraphMaterializationStatus.MERGED,
            GraphMaterializationMode.AUTO,
            3,
            2,
            3,
            2,
            null,
            now,
            now,
            null
        ));
        journals.appendChunks("doc-1", List.of(
            new DocumentGraphJournalStore.ChunkGraphJournal(
                "doc-1",
                "chunk-1",
                1,
                ChunkMergeStatus.SUCCEEDED,
                ChunkGraphStatus.MATERIALIZED,
                List.of("entity-a"),
                List.of("relation-a"),
                List.of("entity-a"),
                List.of("relation-a"),
                null,
                now,
                null
            )
        ));

        assertThat(journals.listDocumentJournals("doc-1")).hasSize(1);
        assertThat(journals.listChunkJournals("doc-1")).hasSize(1);
    }
}
