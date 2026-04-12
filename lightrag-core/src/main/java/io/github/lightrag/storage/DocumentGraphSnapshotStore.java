package io.github.lightrag.storage;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface DocumentGraphSnapshotStore {
    void saveDocument(DocumentGraphSnapshot snapshot);

    Optional<DocumentGraphSnapshot> loadDocument(String documentId);

    void saveChunks(String documentId, List<ChunkGraphSnapshot> chunks);

    List<ChunkGraphSnapshot> listChunks(String documentId);

    void delete(String documentId);

    record DocumentGraphSnapshot(
        String documentId,
        int snapshotVersion,
        SnapshotStatus snapshotStatus,
        SnapshotSource snapshotSource,
        int chunkCount,
        Instant createdAt,
        Instant updatedAt,
        String errorMessage
    ) {
        public DocumentGraphSnapshot {
            documentId = requireNonBlank(documentId, "documentId");
            if (snapshotVersion < 0) {
                throw new IllegalArgumentException("snapshotVersion must not be negative");
            }
            snapshotStatus = Objects.requireNonNull(snapshotStatus, "snapshotStatus");
            snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
            if (chunkCount < 0) {
                throw new IllegalArgumentException("chunkCount must not be negative");
            }
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    record ChunkGraphSnapshot(
        String documentId,
        String chunkId,
        int chunkOrder,
        String chunkHash,
        ChunkExtractStatus extractStatus,
        List<String> extractedEntityKeys,
        List<String> extractedRelationKeys,
        Instant updatedAt,
        String errorMessage
    ) {
        public ChunkGraphSnapshot {
            documentId = requireNonBlank(documentId, "documentId");
            chunkId = requireNonBlank(chunkId, "chunkId");
            if (chunkOrder < 0) {
                throw new IllegalArgumentException("chunkOrder must not be negative");
            }
            chunkHash = requireNonBlank(chunkHash, "chunkHash");
            extractStatus = Objects.requireNonNull(extractStatus, "extractStatus");
            extractedEntityKeys = List.copyOf(Objects.requireNonNull(extractedEntityKeys, "extractedEntityKeys"));
            extractedRelationKeys = List.copyOf(Objects.requireNonNull(extractedRelationKeys, "extractedRelationKeys"));
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
}
