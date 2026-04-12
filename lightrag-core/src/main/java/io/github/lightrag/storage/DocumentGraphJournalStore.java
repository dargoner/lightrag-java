package io.github.lightrag.storage;

import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public interface DocumentGraphJournalStore {
    void appendDocument(DocumentGraphJournal journal);

    List<DocumentGraphJournal> listDocumentJournals(String documentId);

    void appendChunks(String documentId, List<ChunkGraphJournal> journals);

    List<ChunkGraphJournal> listChunkJournals(String documentId);

    void delete(String documentId);

    record DocumentGraphJournal(
        String documentId,
        int snapshotVersion,
        GraphMaterializationStatus status,
        GraphMaterializationMode lastMode,
        int expectedEntityCount,
        int expectedRelationCount,
        int materializedEntityCount,
        int materializedRelationCount,
        FailureStage lastFailureStage,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage
    ) {
        public DocumentGraphJournal {
            documentId = requireNonBlank(documentId, "documentId");
            if (snapshotVersion < 0) {
                throw new IllegalArgumentException("snapshotVersion must not be negative");
            }
            status = Objects.requireNonNull(status, "status");
            lastMode = Objects.requireNonNull(lastMode, "lastMode");
            expectedEntityCount = requireNonNegative(expectedEntityCount, "expectedEntityCount");
            expectedRelationCount = requireNonNegative(expectedRelationCount, "expectedRelationCount");
            materializedEntityCount = requireNonNegative(materializedEntityCount, "materializedEntityCount");
            materializedRelationCount = requireNonNegative(materializedRelationCount, "materializedRelationCount");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    record ChunkGraphJournal(
        String documentId,
        String chunkId,
        int snapshotVersion,
        ChunkMergeStatus mergeStatus,
        ChunkGraphStatus graphStatus,
        List<String> expectedEntityKeys,
        List<String> expectedRelationKeys,
        List<String> materializedEntityKeys,
        List<String> materializedRelationKeys,
        FailureStage lastFailureStage,
        Instant updatedAt,
        String errorMessage
    ) {
        public ChunkGraphJournal {
            documentId = requireNonBlank(documentId, "documentId");
            chunkId = requireNonBlank(chunkId, "chunkId");
            if (snapshotVersion < 0) {
                throw new IllegalArgumentException("snapshotVersion must not be negative");
            }
            mergeStatus = Objects.requireNonNull(mergeStatus, "mergeStatus");
            graphStatus = Objects.requireNonNull(graphStatus, "graphStatus");
            expectedEntityKeys = List.copyOf(Objects.requireNonNull(expectedEntityKeys, "expectedEntityKeys"));
            expectedRelationKeys = List.copyOf(Objects.requireNonNull(expectedRelationKeys, "expectedRelationKeys"));
            materializedEntityKeys = List.copyOf(Objects.requireNonNull(materializedEntityKeys, "materializedEntityKeys"));
            materializedRelationKeys = List.copyOf(Objects.requireNonNull(materializedRelationKeys, "materializedRelationKeys"));
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }
}
