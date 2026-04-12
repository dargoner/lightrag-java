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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryStorageProviderTest {
    @Test
    void providerExposesConsistentStoreInstances() {
        var provider = InMemoryStorageProvider.create();

        assertThat(provider.documentStore()).isSameAs(provider.documentStore());
        assertThat(provider.chunkStore()).isSameAs(provider.chunkStore());
        assertThat(provider.graphStore()).isSameAs(provider.graphStore());
        assertThat(provider.vectorStore()).isSameAs(provider.vectorStore());
        assertThat(provider.snapshotStore()).isSameAs(provider.snapshotStore());
        assertThat(provider.documentGraphSnapshotStore()).isSameAs(provider.documentGraphSnapshotStore());
        assertThat(provider.documentGraphJournalStore()).isSameAs(provider.documentGraphJournalStore());
    }

    @Test
    void writeAtomicallyRestoresAllStoresWhenOperationFails() {
        var provider = InMemoryStorageProvider.create();
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
        var originalVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d));

        provider.documentStore().save(originalDocument);
        provider.chunkStore().save(originalChunk);
        provider.graphStore().saveEntity(originalEntity);
        provider.graphStore().saveRelation(originalRelation);
        provider.vectorStore().saveAll("chunks", List.of(originalVector));

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
            storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d))));
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(provider.documentStore().list()).containsExactly(originalDocument);
        assertThat(provider.chunkStore().list()).containsExactly(originalChunk);
        assertThat(provider.graphStore().allEntities()).containsExactly(originalEntity);
        assertThat(provider.graphStore().allRelations()).containsExactly(originalRelation);
        assertThat(provider.vectorStore().list("chunks")).containsExactly(originalVector);
    }

    @Test
    void writeAtomicallyRestoresDocumentGraphStoresWhenOperationFails() {
        var provider = InMemoryStorageProvider.create();
        var originalSnapshot = documentSnapshot("doc-0", Instant.parse("2026-04-12T08:00:00Z"));
        var originalChunkSnapshot = chunkSnapshot("doc-0", "doc-0:0", Instant.parse("2026-04-12T08:00:01Z"));
        var originalDocumentJournal = documentJournal("doc-0", 1, Instant.parse("2026-04-12T08:00:02Z"));
        var originalChunkJournal = chunkJournal("doc-0", "doc-0:0", 1, Instant.parse("2026-04-12T08:00:03Z"));
        provider.documentGraphSnapshotStore().saveDocument(originalSnapshot);
        provider.documentGraphSnapshotStore().saveChunks("doc-0", List.of(originalChunkSnapshot));
        provider.documentGraphJournalStore().appendDocument(originalDocumentJournal);
        provider.documentGraphJournalStore().appendChunks("doc-0", List.of(originalChunkJournal));

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            provider.documentGraphSnapshotStore().saveDocument(documentSnapshot("doc-1", Instant.parse("2026-04-12T08:01:00Z")));
            provider.documentGraphSnapshotStore().saveChunks(
                "doc-1",
                List.of(chunkSnapshot("doc-1", "doc-1:0", Instant.parse("2026-04-12T08:01:01Z")))
            );
            provider.documentGraphJournalStore().appendDocument(
                documentJournal("doc-1", 2, Instant.parse("2026-04-12T08:01:02Z"))
            );
            provider.documentGraphJournalStore().appendChunks(
                "doc-1",
                List.of(chunkJournal("doc-1", "doc-1:0", 2, Instant.parse("2026-04-12T08:01:03Z")))
            );
            throw new IllegalStateException("boom");
        }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("boom");

        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-0")).contains(originalSnapshot);
        assertThat(provider.documentGraphSnapshotStore().listChunks("doc-0")).containsExactly(originalChunkSnapshot);
        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-1")).isEmpty();
        assertThat(provider.documentGraphSnapshotStore().listChunks("doc-1")).isEmpty();
        assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-0")).containsExactly(originalDocumentJournal);
        assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-0")).containsExactly(originalChunkJournal);
        assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-1")).isEmpty();
        assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-1")).isEmpty();
    }

    @Test
    void restoreReplacesDocumentGraphStores() {
        var provider = InMemoryStorageProvider.create();
        provider.documentGraphSnapshotStore().saveDocument(documentSnapshot("doc-old", Instant.parse("2026-04-12T08:00:00Z")));
        provider.documentGraphSnapshotStore().saveChunks(
            "doc-old",
            List.of(chunkSnapshot("doc-old", "doc-old:0", Instant.parse("2026-04-12T08:00:01Z")))
        );
        provider.documentGraphJournalStore().appendDocument(documentJournal("doc-old", 1, Instant.parse("2026-04-12T08:00:02Z")));
        provider.documentGraphJournalStore().appendChunks(
            "doc-old",
            List.of(chunkJournal("doc-old", "doc-old:0", 1, Instant.parse("2026-04-12T08:00:03Z")))
        );

        var replacementSnapshot = documentSnapshot("doc-1", Instant.parse("2026-04-12T09:00:00Z"));
        var replacementChunkSnapshot = chunkSnapshot("doc-1", "doc-1:0", Instant.parse("2026-04-12T09:00:01Z"));
        var replacementDocumentJournal = documentJournal("doc-1", 2, Instant.parse("2026-04-12T09:00:02Z"));
        var replacementChunkJournal = chunkJournal("doc-1", "doc-1:0", 2, Instant.parse("2026-04-12T09:00:03Z"));

        provider.restore(new SnapshotStore.Snapshot(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            List.of(replacementSnapshot),
            List.of(replacementChunkSnapshot),
            List.of(replacementDocumentJournal),
            List.of(replacementChunkJournal)
        ));

        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-old")).isEmpty();
        assertThat(provider.documentGraphSnapshotStore().listChunks("doc-old")).isEmpty();
        assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-old")).isEmpty();
        assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-old")).isEmpty();
        assertThat(provider.documentGraphSnapshotStore().loadDocument("doc-1")).contains(replacementSnapshot);
        assertThat(provider.documentGraphSnapshotStore().listChunks("doc-1")).containsExactly(replacementChunkSnapshot);
        assertThat(provider.documentGraphJournalStore().listDocumentJournals("doc-1")).containsExactly(replacementDocumentJournal);
        assertThat(provider.documentGraphJournalStore().listChunkJournals("doc-1")).containsExactly(replacementChunkJournal);
    }

    @Test
    void readersDoNotObservePartialStateDuringAtomicWrite() throws Exception {
        var provider = InMemoryStorageProvider.create();
        var documentSaved = new CountDownLatch(1);
        var readerAttemptedRead = new CountDownLatch(1);
        var continueWriter = new CountDownLatch(1);
        var readerDocuments = new AtomicReference<List<DocumentStore.DocumentRecord>>();
        var readerChunks = new AtomicReference<List<ChunkStore.ChunkRecord>>();
        var failure = new AtomicReference<Throwable>();
        var document = new DocumentStore.DocumentRecord("doc-1", "Title", "body", Map.of());
        var chunk = new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of());

        var writer = new Thread(() -> {
            try {
                provider.writeAtomically(storage -> {
                    storage.documentStore().save(document);
                    documentSaved.countDown();
                    await(continueWriter);
                    storage.chunkStore().save(chunk);
                    return null;
                });
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        writer.start();
        await(documentSaved);

        var reader = new Thread(() -> {
            try {
                readerAttemptedRead.countDown();
                readerDocuments.set(provider.documentStore().list());
                readerChunks.set(provider.chunkStore().list());
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        });

        reader.start();
        await(readerAttemptedRead);
        continueWriter.countDown();
        writer.join();
        reader.join();

        assertThat(failure.get()).isNull();
        assertThat(readerDocuments.get()).containsExactly(document);
        assertThat(readerChunks.get()).containsExactly(chunk);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test interrupted", exception);
        }
    }

    private static DocumentGraphSnapshotStore.DocumentGraphSnapshot documentSnapshot(String documentId, Instant now) {
        return new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            1,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            1,
            now.minusSeconds(1),
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
            1,
            1,
            1,
            1,
            FailureStage.FINALIZING,
            now.minusSeconds(1),
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
