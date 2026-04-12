package io.github.lightrag.indexing;

import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Document;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class DeletionPipeline {
    private final AtomicStorageProvider storageProvider;
    private final IndexingPipeline indexingPipeline;
    private final Path snapshotPath;

    public DeletionPipeline(
        AtomicStorageProvider storageProvider,
        IndexingPipeline indexingPipeline,
        Path snapshotPath
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.indexingPipeline = Objects.requireNonNull(indexingPipeline, "indexingPipeline");
        this.snapshotPath = snapshotPath;
    }

    public void deleteByEntity(String entityName) {
        var normalized = normalize(entityName);
        var snapshot = StorageSnapshots.capture(storageProvider);
        var entityIds = resolveEntityIds(snapshot.entities(), normalized);
        if (entityIds.isEmpty()) {
            return;
        }

        var relationsToRemove = snapshot.relations().stream()
            .filter(relation -> entityIds.contains(relation.sourceEntityId()) || entityIds.contains(relation.targetEntityId()))
            .map(GraphStore.RelationRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities().stream().filter(entity -> !entityIds.contains(entity.id())).toList(),
            snapshot.relations().stream().filter(relation -> !relationsToRemove.contains(relation.id())).toList(),
            Map.of(
                StorageSnapshots.CHUNK_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.CHUNK_NAMESPACE, List.of()),
                StorageSnapshots.ENTITY_NAMESPACE, filterVectors(snapshot, StorageSnapshots.ENTITY_NAMESPACE, id -> !entityIds.contains(id)),
                StorageSnapshots.RELATION_NAMESPACE, filterVectors(snapshot, StorageSnapshots.RELATION_NAMESPACE, id -> !relationsToRemove.contains(id))
            ),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
    }

    public void deleteByRelation(String sourceEntityName, String targetEntityName) {
        var snapshot = StorageSnapshots.capture(storageProvider);
        var sourceIds = resolveEntityIds(snapshot.entities(), normalize(sourceEntityName));
        var targetIds = resolveEntityIds(snapshot.entities(), normalize(targetEntityName));
        if (sourceIds.isEmpty() || targetIds.isEmpty()) {
            return;
        }

        var relationsToRemove = snapshot.relations().stream()
            .filter(relation -> matchesEndpoints(relation, sourceIds, targetIds))
            .map(GraphStore.RelationRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (relationsToRemove.isEmpty()) {
            return;
        }

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities(),
            snapshot.relations().stream().filter(relation -> !relationsToRemove.contains(relation.id())).toList(),
            Map.of(
                StorageSnapshots.CHUNK_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.CHUNK_NAMESPACE, List.of()),
                StorageSnapshots.ENTITY_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.ENTITY_NAMESPACE, List.of()),
                StorageSnapshots.RELATION_NAMESPACE, filterVectors(snapshot, StorageSnapshots.RELATION_NAMESPACE, id -> !relationsToRemove.contains(id))
            ),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
    }

    public void deleteByDocumentId(String documentId) {
        var targetId = Objects.requireNonNull(documentId, "documentId").strip();
        if (targetId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }

        var beforeSnapshot = StorageSnapshots.capture(storageProvider);
        var remainingDocuments = beforeSnapshot.documents().stream()
            .filter(document -> !document.id().equals(targetId))
            .map(DeletionPipeline::toDocument)
            .toList();
        var remainingDocumentIds = remainingDocuments.stream()
            .map(Document::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var preservedStatuses = beforeSnapshot.documentStatuses().stream()
            .filter(statusRecord -> !statusRecord.documentId().equals(targetId))
            .filter(statusRecord -> !remainingDocumentIds.contains(statusRecord.documentId()))
            .toList();
        if (remainingDocuments.size() == beforeSnapshot.documents().size()) {
            return;
        }

        try {
            storageProvider.restore(StorageSnapshots.empty());
            if (remainingDocuments.isEmpty()) {
                restoreStatuses(preservedStatuses);
                StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
                return;
            }
            indexingPipeline.ingest(remainingDocuments);
            restoreStatuses(preservedStatuses);
            StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        } catch (RuntimeException | Error failure) {
            try {
                storageProvider.restore(beforeSnapshot);
                StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    public void rebuildAllDocuments() {
        var beforeSnapshot = StorageSnapshots.capture(storageProvider);
        var documents = beforeSnapshot.documents().stream()
            .map(DeletionPipeline::toDocument)
            .toList();
        try {
            storageProvider.restore(StorageSnapshots.empty());
            if (!documents.isEmpty()) {
                indexingPipeline.ingest(documents);
            }
            StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        } catch (RuntimeException | Error failure) {
            try {
                storageProvider.restore(beforeSnapshot);
                StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
            } catch (RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
    }

    private static List<VectorStore.VectorRecord> filterVectors(
        SnapshotStore.Snapshot snapshot,
        String namespace,
        Predicate<String> retainPredicate
    ) {
        return snapshot.vectors()
            .getOrDefault(namespace, List.of())
            .stream()
            .filter(vector -> retainPredicate.test(vector.id()))
            .toList();
    }

    private static Set<String> resolveEntityIds(List<GraphStore.EntityRecord> entities, String normalizedName) {
        var matches = new LinkedHashSet<String>();
        for (var entity : entities) {
            if (normalize(entity.name()).equals(normalizedName)) {
                matches.add(entity.id());
                continue;
            }
            if (entity.aliases().stream().map(DeletionPipeline::normalize).anyMatch(normalizedName::equals)) {
                matches.add(entity.id());
            }
        }
        return matches;
    }

    private static boolean matchesEndpoints(
        GraphStore.RelationRecord relation,
        Set<String> sourceIds,
        Set<String> targetIds
    ) {
        return (sourceIds.contains(relation.sourceEntityId()) && targetIds.contains(relation.targetEntityId()))
            || (sourceIds.contains(relation.targetEntityId()) && targetIds.contains(relation.sourceEntityId()));
    }

    private static Document toDocument(DocumentStore.DocumentRecord record) {
        return new Document(record.id(), record.title(), record.content(), record.metadata());
    }

    private void restoreStatuses(List<io.github.lightrag.storage.DocumentStatusStore.StatusRecord> statusRecords) {
        for (var statusRecord : statusRecords) {
            storageProvider.documentStatusStore().save(statusRecord);
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }
}
