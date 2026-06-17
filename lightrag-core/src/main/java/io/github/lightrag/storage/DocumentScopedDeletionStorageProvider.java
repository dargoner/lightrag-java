package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;

public interface DocumentScopedDeletionStorageProvider {
    DocumentDeletionResult deleteDocumentDerivedState(String documentId, List<String> chunkIds);

    record DocumentDeletionResult(
        String documentId,
        int chunkCount,
        int documentsDeleted,
        int chunksDeleted,
        int statusesDeleted,
        int graphSnapshotsDeleted,
        int graphJournalsDeleted,
        int entitiesDeleted,
        int entitiesUpdated,
        int relationsDeleted,
        int relationsUpdated,
        int vectorNamespacesTouched
    ) {
        public DocumentDeletionResult {
            documentId = Objects.requireNonNull(documentId, "documentId");
            requireNonNegative(chunkCount, "chunkCount");
            requireNonNegative(documentsDeleted, "documentsDeleted");
            requireNonNegative(chunksDeleted, "chunksDeleted");
            requireNonNegative(statusesDeleted, "statusesDeleted");
            requireNonNegative(graphSnapshotsDeleted, "graphSnapshotsDeleted");
            requireNonNegative(graphJournalsDeleted, "graphJournalsDeleted");
            requireNonNegative(entitiesDeleted, "entitiesDeleted");
            requireNonNegative(entitiesUpdated, "entitiesUpdated");
            requireNonNegative(relationsDeleted, "relationsDeleted");
            requireNonNegative(relationsUpdated, "relationsUpdated");
            requireNonNegative(vectorNamespacesTouched, "vectorNamespacesTouched");
        }

        private static void requireNonNegative(int value, String fieldName) {
            if (value < 0) {
                throw new IllegalArgumentException(fieldName + " must be non-negative");
            }
        }
    }
}
