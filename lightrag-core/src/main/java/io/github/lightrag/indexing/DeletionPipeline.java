package io.github.lightrag.indexing;

import io.github.lightrag.api.DeletionResult;
import io.github.lightrag.api.DeleteDocumentOptions;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.LlmCacheStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Document;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public final class DeletionPipeline {
    public static final String METADATA_DELETION_LLM_CACHE_IDS = "deletion_llm_cache_ids";
    public static final String METADATA_LAST_DELETION_ATTEMPT_AT = "last_deletion_attempt_at";
    public static final String METADATA_DELETION_FAILED = "deletion_failed";
    public static final String METADATA_DELETION_FAILURE_STAGE = "deletion_failure_stage";
    public static final String CHUNK_METADATA_LLM_CACHE_LIST = "llm_cache_list";

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

    public DeletionResult deleteByEntity(String entityName) {
        var normalized = normalize(entityName);
        var snapshot = StorageSnapshots.capture(storageProvider);
        var entityIds = resolveEntityIds(snapshot.entities(), normalized);
        if (entityIds.isEmpty()) {
            return DeletionResult.notFound(entityName, "Entity '" + entityName + "' not found.");
        }

        var relationsToRemove = snapshot.relations().stream()
            .filter(relation -> entityIds.contains(relation.srcId()) || entityIds.contains(relation.tgtId()))
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
            filterChunkGraphSnapshotsForEntities(snapshot.chunkGraphSnapshots(), entityIds),
            snapshot.documentGraphJournals(),
            filterChunkGraphJournals(snapshot.chunkGraphJournals(), entityIds, relationsToRemove)
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return DeletionResult.success(
            entityName,
            "Entity Delete: remove '" + entityName + "' and its " + relationsToRemove.size() + " relations"
        );
    }

    public DeletionResult deleteByRelation(String sourceEntityName, String targetEntityName) {
        var snapshot = StorageSnapshots.capture(storageProvider);
        var sourceIds = resolveEntityIds(snapshot.entities(), normalize(sourceEntityName));
        var targetIds = resolveEntityIds(snapshot.entities(), normalize(targetEntityName));
        if (sourceIds.isEmpty() || targetIds.isEmpty()) {
            return DeletionResult.notFound(
                sourceEntityName + " -> " + targetEntityName,
                "Relation from '" + sourceEntityName + "' to '" + targetEntityName + "' does not exist"
            );
        }

        var relationsToRemove = snapshot.relations().stream()
            .filter(relation -> matchesEndpoints(relation, sourceIds, targetIds))
            .map(GraphStore.RelationRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (relationsToRemove.isEmpty()) {
            return DeletionResult.notFound(
                sourceEntityName + " -> " + targetEntityName,
                "Relation from '" + sourceEntityName + "' to '" + targetEntityName + "' does not exist"
            );
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
            filterChunkGraphSnapshotsForRelations(snapshot.chunkGraphSnapshots(), relationsToRemove),
            snapshot.documentGraphJournals(),
            filterChunkGraphJournals(snapshot.chunkGraphJournals(), Set.of(), relationsToRemove)
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return DeletionResult.success(
            sourceEntityName + " -> " + targetEntityName,
            "Relation Delete: `" + sourceEntityName + "`~`" + targetEntityName + "` deleted successfully"
        );
    }

    public DeletionResult deleteByDocumentId(String documentId) {
        return deleteByDocumentId(documentId, DeleteDocumentOptions.defaults());
    }

    public DeletionResult deleteByDocumentId(String documentId, DeleteDocumentOptions options) {
        var targetId = Objects.requireNonNull(documentId, "documentId").strip();
        if (targetId.isEmpty()) {
            throw new IllegalArgumentException("documentId must not be blank");
        }
        var deleteOptions = Objects.requireNonNull(options, "options");

        var beforeSnapshot = StorageSnapshots.capture(storageProvider);
        var targetStatus = beforeSnapshot.documentStatuses().stream()
            .filter(statusRecord -> statusRecord.documentId().equals(targetId))
            .findFirst();
        var targetStatusRecord = targetStatus.orElse(null);
        var cacheIds = collectDeletionLlmCacheIds(beforeSnapshot, targetId, targetStatusRecord);
        var deletionStage = "initializing";
        if (deleteOptions.deleteLlmCache() && !cacheIds.isEmpty()) {
            requireLlmCacheStore(targetId, retryStatusRecord(targetId, targetStatusRecord), cacheIds);
        }
        var targetChunkIds = beforeSnapshot.chunks().stream()
            .filter(chunk -> chunk.documentId().equals(targetId))
            .map(io.github.lightrag.storage.ChunkStore.ChunkRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var targetDocumentExists = beforeSnapshot.documents().stream().anyMatch(document -> document.id().equals(targetId));
        if (!targetDocumentExists) {
            if (targetStatus.isPresent()) {
                deletionStage = "delete_llm_cache";
                if (deleteOptions.deleteLlmCache() && !cacheIds.isEmpty()) {
                    targetStatusRecord = updateDeleteRetryState(
                        targetStatusRecord,
                        deletionStage,
                        cacheIds,
                        false,
                        null
                    );
                    deleteLlmCacheOrMarkRetryFailed(targetId, targetStatusRecord, cacheIds);
                }
                deletionStage = "delete_doc_entries";
                storageProvider.documentStatusStore().delete(targetId);
                StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
                return DeletionResult.success(
                    targetId,
                    "Document deleted without associated chunks: " + targetId
                );
            }
            return DeletionResult.notFound(targetId, "Document " + targetId + " not found.");
        }

        if (!cacheIds.isEmpty()) {
            targetStatusRecord = updateDeleteRetryState(
                retryStatusRecord(targetId, targetStatusRecord),
                "collect_llm_cache",
                cacheIds,
                false,
                null
            );
        }

        try {
            deletionStage = "delete_document_derived_state";
            storageProvider.restore(removeDocumentIncrementally(
                beforeSnapshot,
                targetId,
                targetChunkIds,
                targetStatusRecord
            ));

            if (deleteOptions.deleteLlmCache() && !cacheIds.isEmpty()) {
                deletionStage = "delete_llm_cache";
                var retryStatusRecord = retryStatusRecord(targetId, targetStatusRecord);
                retryStatusRecord = updateDeleteRetryState(
                    retryStatusRecord,
                    deletionStage,
                    cacheIds,
                    false,
                    null
                );
                deleteLlmCacheOrMarkRetryFailed(targetId, retryStatusRecord, cacheIds);
            }
            deletionStage = "delete_doc_entries";
            storageProvider.documentStatusStore().delete(targetId);
            StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
            return DeletionResult.success(targetId, "Document " + targetId + " successfully deleted");
        } catch (RuntimeException | Error failure) {
            try {
                updateDeleteRetryState(
                    retryStatusRecord(targetId, targetStatusRecord),
                    deletionStage,
                    cacheIds,
                    true,
                    failure.getMessage()
                );
                StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
            } catch (RuntimeException | Error statusUpdateFailure) {
                failure.addSuppressed(statusUpdateFailure);
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

    private static SnapshotStore.Snapshot removeDocumentIncrementally(
        SnapshotStore.Snapshot snapshot,
        String documentId,
        Set<String> targetChunkIds,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord retainedStatusRecord
    ) {
        var chunkIds = Set.copyOf(Objects.requireNonNull(targetChunkIds, "targetChunkIds"));
        var entities = snapshot.entities().stream()
            .map(entity -> removeEntityChunkReferences(entity, chunkIds))
            .filter(entity -> !entity.sourceChunkIds().isEmpty())
            .toList();
        var entityIds = entities.stream()
            .map(GraphStore.EntityRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var relations = snapshot.relations().stream()
            .map(relation -> removeRelationChunkReferences(relation, chunkIds))
            .filter(relation -> !relation.sourceChunkIds().isEmpty())
            .filter(relation -> entityIds.contains(relation.srcId()) && entityIds.contains(relation.tgtId()))
            .toList();
        var relationIds = relations.stream()
            .map(GraphStore.RelationRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var vectors = new java.util.LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        snapshot.vectors().forEach((namespace, records) -> vectors.put(namespace, records));
        vectors.put(
            StorageSnapshots.CHUNK_NAMESPACE,
            filterVectors(snapshot, StorageSnapshots.CHUNK_NAMESPACE, id -> !chunkIds.contains(id))
        );
        vectors.put(
            StorageSnapshots.ENTITY_NAMESPACE,
            filterVectors(snapshot, StorageSnapshots.ENTITY_NAMESPACE, entityIds::contains)
        );
        vectors.put(
            StorageSnapshots.RELATION_NAMESPACE,
            filterVectors(snapshot, StorageSnapshots.RELATION_NAMESPACE, relationIds::contains)
        );
        return new SnapshotStore.Snapshot(
            snapshot.documents().stream().filter(document -> !document.id().equals(documentId)).toList(),
            snapshot.chunks().stream().filter(chunk -> !chunk.documentId().equals(documentId)).toList(),
            entities,
            relations,
            Map.copyOf(vectors),
            retainDocumentStatus(snapshot.documentStatuses(), documentId, retainedStatusRecord),
            snapshot.documentGraphSnapshots().stream().filter(status -> !status.documentId().equals(documentId)).toList(),
            snapshot.chunkGraphSnapshots().stream().filter(chunk -> !chunk.documentId().equals(documentId)).toList(),
            snapshot.documentGraphJournals().stream().filter(journal -> !journal.documentId().equals(documentId)).toList(),
            snapshot.chunkGraphJournals().stream().filter(journal -> !journal.documentId().equals(documentId)).toList()
        );
    }

    private static List<io.github.lightrag.storage.DocumentStatusStore.StatusRecord> retainDocumentStatus(
        List<io.github.lightrag.storage.DocumentStatusStore.StatusRecord> statusRecords,
        String documentId,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord retainedStatusRecord
    ) {
        if (retainedStatusRecord == null) {
            return statusRecords;
        }
        var retained = new java.util.ArrayList<io.github.lightrag.storage.DocumentStatusStore.StatusRecord>();
        var replaced = false;
        for (var statusRecord : statusRecords) {
            if (statusRecord.documentId().equals(documentId)) {
                retained.add(retainedStatusRecord);
                replaced = true;
            } else {
                retained.add(statusRecord);
            }
        }
        if (!replaced) {
            retained.add(retainedStatusRecord);
        }
        return List.copyOf(retained);
    }

    private static GraphStore.EntityRecord removeEntityChunkReferences(
        GraphStore.EntityRecord entity,
        Set<String> targetChunkIds
    ) {
        return new GraphStore.EntityRecord(
            entity.id(),
            entity.name(),
            entity.type(),
            entity.description(),
            entity.aliases(),
            entity.sourceChunkIds().stream().filter(chunkId -> !targetChunkIds.contains(chunkId)).toList()
        );
    }

    private static GraphStore.RelationRecord removeRelationChunkReferences(
        GraphStore.RelationRecord relation,
        Set<String> targetChunkIds
    ) {
        return new GraphStore.RelationRecord(
            relation.id(),
            relation.srcId(),
            relation.tgtId(),
            relation.keywords(),
            relation.description(),
            relation.weight(),
            relation.sourceChunkIds().stream().filter(chunkId -> !targetChunkIds.contains(chunkId)).toList()
        );
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

    private static List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> filterChunkGraphSnapshotsForEntities(
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> snapshots,
        Set<String> entityIds
    ) {
        if (entityIds.isEmpty()) {
            return snapshots;
        }
        return snapshots.stream()
            .map(snapshot -> new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                snapshot.documentId(),
                snapshot.chunkId(),
                snapshot.chunkOrder(),
                snapshot.contentHash(),
                snapshot.extractStatus(),
                snapshot.entities().stream()
                    .filter(entity -> !entityIds.contains(normalize(entity.name())))
                    .toList(),
                snapshot.relations().stream()
                    .filter(relation -> !relationTouchesAny(relation, entityIds))
                    .toList(),
                snapshot.updatedAt(),
                snapshot.errorMessage()
            ))
            .toList();
    }

    private static List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> filterChunkGraphSnapshotsForRelations(
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> snapshots,
        Set<String> relationIds
    ) {
        if (relationIds.isEmpty()) {
            return snapshots;
        }
        return snapshots.stream()
            .map(snapshot -> new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                snapshot.documentId(),
                snapshot.chunkId(),
                snapshot.chunkOrder(),
                snapshot.contentHash(),
                snapshot.extractStatus(),
                snapshot.entities(),
                snapshot.relations().stream()
                    .filter(relation -> !relationIds.contains(relationId(relation)))
                    .toList(),
                snapshot.updatedAt(),
                snapshot.errorMessage()
            ))
            .toList();
    }

    private static List<DocumentGraphJournalStore.ChunkGraphJournal> filterChunkGraphJournals(
        List<DocumentGraphJournalStore.ChunkGraphJournal> journals,
        Set<String> entityIds,
        Set<String> relationIds
    ) {
        if (entityIds.isEmpty() && relationIds.isEmpty()) {
            return journals;
        }
        return journals.stream()
            .map(journal -> new DocumentGraphJournalStore.ChunkGraphJournal(
                journal.documentId(),
                journal.chunkId(),
                journal.snapshotVersion(),
                journal.mergeStatus(),
                journal.graphStatus(),
                filterIds(journal.expectedEntityKeys(), entityIds),
                filterIds(journal.expectedRelationKeys(), relationIds),
                filterIds(journal.materializedEntityKeys(), entityIds),
                filterIds(journal.materializedRelationKeys(), relationIds),
                journal.lastFailureStage(),
                journal.updatedAt(),
                journal.errorMessage()
            ))
            .toList();
    }

    private static List<String> filterIds(List<String> ids, Set<String> idsToRemove) {
        if (idsToRemove.isEmpty()) {
            return ids;
        }
        return ids.stream()
            .filter(id -> !idsToRemove.contains(id))
            .toList();
    }

    private static boolean relationTouchesAny(
        DocumentGraphSnapshotStore.ExtractedRelationRecord relation,
        Set<String> entityIds
    ) {
        return entityIds.contains(normalize(relation.sourceEntityName()))
            || entityIds.contains(normalize(relation.targetEntityName()));
    }

    private static String relationId(DocumentGraphSnapshotStore.ExtractedRelationRecord relation) {
        return RelationCanonicalizer.relationId(
            normalize(relation.sourceEntityName()),
            normalize(relation.targetEntityName())
        );
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
        return (sourceIds.contains(relation.srcId()) && targetIds.contains(relation.tgtId()))
            || (sourceIds.contains(relation.tgtId()) && targetIds.contains(relation.srcId()));
    }

    private static Document toDocument(DocumentStore.DocumentRecord record) {
        return new Document(record.id(), record.title(), record.content(), record.metadata());
    }

    private void restoreStatuses(List<io.github.lightrag.storage.DocumentStatusStore.StatusRecord> statusRecords) {
        for (var statusRecord : statusRecords) {
            storageProvider.documentStatusStore().save(statusRecord);
        }
    }

    private void deleteLlmCacheOrMarkRetryFailed(
        String documentId,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord,
        List<String> cacheIds
    ) {
        if (cacheIds.isEmpty()) {
            return;
        }
        var cacheStore = requireLlmCacheStore(documentId, statusRecord, cacheIds);
        try {
            cacheStore.delete(cacheIds);
            var remainingCacheIds = cacheIds.stream()
                .filter(cacheStore::contains)
                .toList();
            if (!remainingCacheIds.isEmpty()) {
                throw new IllegalStateException(remainingCacheIds.size() + " LLM cache entries still exist after delete");
            }
            updateDeleteRetryState(statusRecord, "delete_llm_cache", cacheIds, false, null);
        } catch (RuntimeException failure) {
            var message = "Failed to delete LLM cache for document " + documentId + ": " + failure.getMessage();
            updateDeleteRetryState(statusRecord, "delete_llm_cache", cacheIds, true, message);
            throw new IllegalStateException(message, failure);
        }
    }

    private LlmCacheStore requireLlmCacheStore(
        String documentId,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord,
        List<String> cacheIds
    ) {
        try {
            return storageProvider.llmCacheStore();
        } catch (UnsupportedOperationException exception) {
            if (!StorageProvider.LLM_CACHE_STORE_UNSUPPORTED_MESSAGE.equals(exception.getMessage())) {
                throw exception;
            }
            var message = "LLM cache deletion requested for document " + documentId
                + " but Java LLM cache storage is not configured";
            updateDeleteRetryState(statusRecord, "delete_llm_cache", cacheIds, true, message);
            throw new UnsupportedOperationException(message, exception);
        }
    }

    private io.github.lightrag.storage.DocumentStatusStore.StatusRecord updateDeleteRetryState(
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord,
        String deletionStage,
        List<String> cacheIds,
        boolean failed,
        String errorMessage
    ) {
        var metadata = new java.util.LinkedHashMap<String, Object>(statusRecord.metadata());
        if (!cacheIds.isEmpty()) {
            metadata.put(METADATA_DELETION_LLM_CACHE_IDS, List.copyOf(cacheIds));
        }
        metadata.put(METADATA_LAST_DELETION_ATTEMPT_AT, Instant.now().toString());
        if (failed) {
            metadata.put(METADATA_DELETION_FAILED, true);
            metadata.put(METADATA_DELETION_FAILURE_STAGE, deletionStage);
        } else {
            metadata.remove(METADATA_DELETION_FAILED);
            metadata.remove(METADATA_DELETION_FAILURE_STAGE);
        }
        var updated = new io.github.lightrag.storage.DocumentStatusStore.StatusRecord(
            statusRecord.documentId(),
            statusRecord.status(),
            statusRecord.summary(),
            failed ? errorMessage : statusRecord.errorMessage(),
            metadata
        );
        storageProvider.documentStatusStore().save(updated);
        return updated;
    }

    private static io.github.lightrag.storage.DocumentStatusStore.StatusRecord retryStatusRecord(
        String documentId,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord
    ) {
        if (statusRecord != null) {
            return statusRecord;
        }
        return new io.github.lightrag.storage.DocumentStatusStore.StatusRecord(
            documentId,
            io.github.lightrag.api.DocumentStatus.FAILED,
            "",
            "document status did not exist before deletion"
        );
    }

    private static List<String> collectDeletionLlmCacheIds(
        SnapshotStore.Snapshot snapshot,
        String documentId,
        io.github.lightrag.storage.DocumentStatusStore.StatusRecord statusRecord
    ) {
        var ids = new LinkedHashSet<String>();
        if (statusRecord != null) {
            appendCacheIds(ids, statusRecord.metadata().get(METADATA_DELETION_LLM_CACHE_IDS));
        }
        snapshot.chunks().stream()
            .filter(chunk -> chunk.documentId().equals(documentId))
            .map(DeletionPipeline::cacheIds)
            .forEach(ids::addAll);
        return List.copyOf(ids);
    }

    private static List<String> cacheIds(io.github.lightrag.storage.ChunkStore.ChunkRecord chunk) {
        var ids = new LinkedHashSet<String>();
        appendCacheIds(ids, chunk.metadata().get(CHUNK_METADATA_LLM_CACHE_LIST));
        return List.copyOf(ids);
    }

    private static void appendCacheIds(Set<String> out, Object rawValue) {
        if (rawValue instanceof Iterable<?> iterable) {
            for (var item : iterable) {
                if (item instanceof String value && !value.isBlank()) {
                    out.add(value.strip());
                }
            }
            return;
        }
        if (rawValue instanceof String stringValue) {
            var normalized = stringValue.strip();
            if (normalized.isEmpty()) {
                return;
            }
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                for (var candidate : normalized.substring(1, normalized.length() - 1).split(",")) {
                    var value = candidate.strip().replaceAll("^\"|\"$", "");
                    if (!value.isBlank()) {
                        out.add(value);
                    }
                }
                return;
            }
            for (var candidate : normalized.split(",")) {
                var value = candidate.strip();
                if (!value.isBlank()) {
                    out.add(value);
                }
            }
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

}
