package io.github.lightrag.indexing;

import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.SnapshotStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class StorageSnapshots {
    public static final String CHUNK_NAMESPACE = "chunks";
    public static final String ENTITY_NAMESPACE = "entities";
    public static final String RELATION_NAMESPACE = "relations";

    private StorageSnapshots() {
    }

    public static SnapshotStore.Snapshot capture(AtomicStorageProvider storageProvider) {
        var provider = Objects.requireNonNull(storageProvider, "storageProvider");
        var documents = provider.documentStore().list();
        var documentStatuses = provider.documentStatusStore().list();
        var documentGraphState = DocumentGraphStateSupport.capture(
            provider.documentGraphSnapshotStore(),
            provider.documentGraphJournalStore(),
            java.util.List.of(),
            documents,
            documentStatuses
        );
        return new SnapshotStore.Snapshot(
            documents,
            provider.chunkStore().list(),
            provider.graphStore().allEntities(),
            provider.graphStore().allRelations(),
            Map.of(
                CHUNK_NAMESPACE, provider.vectorStore().list(CHUNK_NAMESPACE),
                ENTITY_NAMESPACE, provider.vectorStore().list(ENTITY_NAMESPACE),
                RELATION_NAMESPACE, provider.vectorStore().list(RELATION_NAMESPACE)
            ),
            documentStatuses,
            documentGraphState.documentSnapshots(),
            documentGraphState.chunkSnapshots(),
            documentGraphState.documentJournals(),
            documentGraphState.chunkJournals()
        );
    }

    public static void persistIfConfigured(AtomicStorageProvider storageProvider, Path snapshotPath) {
        if (snapshotPath == null) {
            return;
        }
        storageProvider.snapshotStore().save(snapshotPath, capture(storageProvider));
    }

    public static SnapshotStore.Snapshot empty() {
        return new SnapshotStore.Snapshot(
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            Map.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of()
        );
    }
}
