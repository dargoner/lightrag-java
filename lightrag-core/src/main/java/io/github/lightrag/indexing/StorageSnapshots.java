package io.github.lightrag.indexing;

import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.SnapshotStore;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StorageSnapshots {
    private static final Logger log = LoggerFactory.getLogger(StorageSnapshots.class);
    public static final String CHUNK_NAMESPACE = "chunks";
    public static final String ENTITY_NAMESPACE = "entities";
    public static final String RELATION_NAMESPACE = "relations";

    private StorageSnapshots() {
    }

    public static SnapshotStore.Snapshot capture(AtomicStorageProvider storageProvider) {
        var provider = Objects.requireNonNull(storageProvider, "storageProvider");
        long started = System.nanoTime();
        var documents = provider.documentStore().list();
        long documentsAt = System.nanoTime();
        var documentStatuses = provider.documentStatusStore().list();
        long statusesAt = System.nanoTime();
        var documentGraphState = DocumentGraphStateSupport.capture(
            provider.documentGraphSnapshotStore(),
            provider.documentGraphJournalStore(),
            java.util.List.of(),
            documents,
            documentStatuses
        );
        long graphStateAt = System.nanoTime();
        var chunks = provider.chunkStore().list();
        long chunksAt = System.nanoTime();
        var entities = provider.graphStore().allEntities();
        long entitiesAt = System.nanoTime();
        var relations = provider.graphStore().allRelations();
        long relationsAt = System.nanoTime();
        var chunkVectors = provider.vectorStore().list(CHUNK_NAMESPACE);
        long chunkVectorsAt = System.nanoTime();
        var entityVectors = provider.vectorStore().list(ENTITY_NAMESPACE);
        long entityVectorsAt = System.nanoTime();
        var relationVectors = provider.vectorStore().list(RELATION_NAMESPACE);
        long relationVectorsAt = System.nanoTime();
        log.info(
            "LightRAG storage snapshot captured: documents={}, chunks={}, entities={}, relations={}, chunkVectors={}, entityVectors={}, relationVectors={}, documentsMs={}, statusesMs={}, graphStateMs={}, chunksMs={}, entitiesMs={}, relationsMs={}, chunkVectorsMs={}, entityVectorsMs={}, relationVectorsMs={}, totalMs={}",
            documents.size(),
            chunks.size(),
            entities.size(),
            relations.size(),
            chunkVectors.size(),
            entityVectors.size(),
            relationVectors.size(),
            elapsedMillis(started, documentsAt),
            elapsedMillis(documentsAt, statusesAt),
            elapsedMillis(statusesAt, graphStateAt),
            elapsedMillis(graphStateAt, chunksAt),
            elapsedMillis(chunksAt, entitiesAt),
            elapsedMillis(entitiesAt, relationsAt),
            elapsedMillis(relationsAt, chunkVectorsAt),
            elapsedMillis(chunkVectorsAt, entityVectorsAt),
            elapsedMillis(entityVectorsAt, relationVectorsAt),
            elapsedMillis(started, relationVectorsAt)
        );
        return new SnapshotStore.Snapshot(
            documents,
            chunks,
            entities,
            relations,
            Map.of(
                CHUNK_NAMESPACE, chunkVectors,
                ENTITY_NAMESPACE, entityVectors,
                RELATION_NAMESPACE, relationVectors
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
        long started = System.nanoTime();
        storageProvider.snapshotStore().save(snapshotPath, capture(storageProvider));
        log.info(
            "LightRAG storage snapshot persisted: path={}, totalMs={}",
            snapshotPath,
            elapsedMillis(started, System.nanoTime())
        );
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

    private static long elapsedMillis(long startedNanos, long endedNanos) {
        return Math.max(0L, (endedNanos - startedNanos) / 1_000_000L);
    }
}
