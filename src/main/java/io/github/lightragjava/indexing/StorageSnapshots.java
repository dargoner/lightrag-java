package io.github.lightragjava.indexing;

import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

final class StorageSnapshots {
    static final String CHUNK_NAMESPACE = "chunks";
    static final String ENTITY_NAMESPACE = "entities";
    static final String RELATION_NAMESPACE = "relations";

    private StorageSnapshots() {
    }

    static SnapshotStore.Snapshot capture(AtomicStorageProvider storageProvider) {
        var provider = Objects.requireNonNull(storageProvider, "storageProvider");
        return new SnapshotStore.Snapshot(
            provider.documentStore().list(),
            provider.chunkStore().list(),
            provider.graphStore().allEntities(),
            provider.graphStore().allRelations(),
            Map.of(
                CHUNK_NAMESPACE, provider.vectorStore().list(CHUNK_NAMESPACE),
                ENTITY_NAMESPACE, provider.vectorStore().list(ENTITY_NAMESPACE),
                RELATION_NAMESPACE, provider.vectorStore().list(RELATION_NAMESPACE)
            )
        );
    }

    static void persistIfConfigured(AtomicStorageProvider storageProvider, Path snapshotPath) {
        if (snapshotPath == null) {
            return;
        }
        storageProvider.snapshotStore().save(snapshotPath, capture(storageProvider));
    }

    static SnapshotStore.Snapshot empty() {
        return new SnapshotStore.Snapshot(
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            Map.of()
        );
    }
}
