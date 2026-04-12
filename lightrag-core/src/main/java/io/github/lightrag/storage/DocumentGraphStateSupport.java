package io.github.lightrag.storage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class DocumentGraphStateSupport {
    private DocumentGraphStateSupport() {
    }

    public static DocumentGraphSnapshotStore trackedSnapshotStore(
        DocumentGraphSnapshotStore delegate,
        Set<String> trackedDocumentIds
    ) {
        var store = Objects.requireNonNull(delegate, "delegate");
        var trackedIds = Objects.requireNonNull(trackedDocumentIds, "trackedDocumentIds");
        return new DocumentGraphSnapshotStore() {
            @Override
            public void saveDocument(DocumentGraphSnapshot snapshot) {
                var value = Objects.requireNonNull(snapshot, "snapshot");
                remember(trackedIds, value.documentId());
                store.saveDocument(value);
            }

            @Override
            public java.util.Optional<DocumentGraphSnapshot> loadDocument(String documentId) {
                return store.loadDocument(documentId);
            }

            @Override
            public void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks) {
                remember(trackedIds, documentId);
                store.saveChunks(documentId, chunks);
            }

            @Override
            public List<ChunkGraphSnapshot> listChunks(String documentId) {
                return store.listChunks(documentId);
            }

            @Override
            public void delete(String documentId) {
                store.delete(documentId);
            }
        };
    }

    public static DocumentGraphJournalStore trackedJournalStore(
        DocumentGraphJournalStore delegate,
        Set<String> trackedDocumentIds
    ) {
        var store = Objects.requireNonNull(delegate, "delegate");
        var trackedIds = Objects.requireNonNull(trackedDocumentIds, "trackedDocumentIds");
        return new DocumentGraphJournalStore() {
            @Override
            public void appendDocument(DocumentGraphJournal journal) {
                var entry = Objects.requireNonNull(journal, "journal");
                remember(trackedIds, entry.documentId());
                store.appendDocument(entry);
            }

            @Override
            public List<DocumentGraphJournal> listDocumentJournals(String documentId) {
                return store.listDocumentJournals(documentId);
            }

            @Override
            public void appendChunks(String documentId, List<ChunkGraphJournal> journals) {
                remember(trackedIds, documentId);
                store.appendChunks(documentId, journals);
            }

            @Override
            public List<ChunkGraphJournal> listChunkJournals(String documentId) {
                return store.listChunkJournals(documentId);
            }

            @Override
            public void delete(String documentId) {
                store.delete(documentId);
            }
        };
    }

    public static SnapshotStore.DocumentGraphState capture(
        DocumentGraphSnapshotStore snapshotStore,
        DocumentGraphJournalStore journalStore,
        Collection<String> trackedDocumentIds,
        Collection<DocumentStore.DocumentRecord> documents,
        Collection<DocumentStatusStore.StatusRecord> statuses
    ) {
        var documentIds = new LinkedHashSet<String>();
        addDocumentIds(documentIds, trackedDocumentIds);
        documents.forEach(document -> documentIds.add(document.id()));
        statuses.forEach(status -> documentIds.add(status.documentId()));
        return capture(snapshotStore, journalStore, documentIds);
    }

    public static SnapshotStore.DocumentGraphState capture(
        DocumentGraphSnapshotStore snapshotStore,
        DocumentGraphJournalStore journalStore,
        Collection<String> documentIds
    ) {
        var snapshots = Objects.requireNonNull(snapshotStore, "snapshotStore");
        var journals = Objects.requireNonNull(journalStore, "journalStore");
        var ids = Objects.requireNonNull(documentIds, "documentIds");
        var documentSnapshots = new java.util.ArrayList<DocumentGraphSnapshotStore.DocumentGraphSnapshot>();
        var chunkSnapshots = new java.util.ArrayList<DocumentGraphSnapshotStore.ChunkGraphSnapshot>();
        var documentJournals = new java.util.ArrayList<DocumentGraphJournalStore.DocumentGraphJournal>();
        var chunkJournals = new java.util.ArrayList<DocumentGraphJournalStore.ChunkGraphJournal>();
        for (var documentId : ids) {
            snapshots.loadDocument(documentId).ifPresent(documentSnapshots::add);
            chunkSnapshots.addAll(snapshots.listChunks(documentId));
            documentJournals.addAll(journals.listDocumentJournals(documentId));
            chunkJournals.addAll(journals.listChunkJournals(documentId));
        }
        return new SnapshotStore.DocumentGraphState(
            documentSnapshots,
            chunkSnapshots,
            documentJournals,
            chunkJournals
        );
    }

    public static void restore(
        DocumentGraphSnapshotStore snapshotStore,
        DocumentGraphJournalStore journalStore,
        Collection<String> trackedDocumentIds,
        SnapshotStore.Snapshot snapshot
    ) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        var documentIdsToDelete = new LinkedHashSet<String>();
        addDocumentIds(documentIdsToDelete, trackedDocumentIds);
        source.documentGraphSnapshots().forEach(record -> documentIdsToDelete.add(record.documentId()));
        source.chunkGraphSnapshots().forEach(record -> documentIdsToDelete.add(record.documentId()));
        source.documentGraphJournals().forEach(record -> documentIdsToDelete.add(record.documentId()));
        source.chunkGraphJournals().forEach(record -> documentIdsToDelete.add(record.documentId()));
        for (var documentId : documentIdsToDelete) {
            snapshotStore.delete(documentId);
            journalStore.delete(documentId);
        }
        source.documentGraphSnapshots().forEach(snapshotStore::saveDocument);
        groupChunkSnapshots(source.chunkGraphSnapshots()).forEach(snapshotStore::saveChunks);
        source.documentGraphJournals().forEach(journalStore::appendDocument);
        groupChunkJournals(source.chunkGraphJournals()).forEach(journalStore::appendChunks);
    }

    private static Map<String, List<DocumentGraphSnapshotStore.ChunkGraphSnapshot>> groupChunkSnapshots(
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> snapshots
    ) {
        var grouped = new LinkedHashMap<String, java.util.ArrayList<DocumentGraphSnapshotStore.ChunkGraphSnapshot>>();
        for (var snapshot : snapshots) {
            grouped.computeIfAbsent(snapshot.documentId(), ignored -> new java.util.ArrayList<>()).add(snapshot);
        }
        return grouped.entrySet().stream().collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue()),
            (left, right) -> right,
            LinkedHashMap::new
        ));
    }

    private static Map<String, List<DocumentGraphJournalStore.ChunkGraphJournal>> groupChunkJournals(
        List<DocumentGraphJournalStore.ChunkGraphJournal> journals
    ) {
        var grouped = new LinkedHashMap<String, java.util.ArrayList<DocumentGraphJournalStore.ChunkGraphJournal>>();
        for (var journal : journals) {
            grouped.computeIfAbsent(journal.documentId(), ignored -> new java.util.ArrayList<>()).add(journal);
        }
        return grouped.entrySet().stream().collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey,
            entry -> List.copyOf(entry.getValue()),
            (left, right) -> right,
            LinkedHashMap::new
        ));
    }

    private static void addDocumentIds(Set<String> target, Collection<String> source) {
        for (var documentId : source) {
            remember(target, documentId);
        }
    }

    private static void remember(Set<String> trackedDocumentIds, String documentId) {
        trackedDocumentIds.add(normalize(documentId));
    }

    private static String normalize(String documentId) {
        var normalized = Objects.requireNonNull(documentId, "documentId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        return normalized;
    }
}
