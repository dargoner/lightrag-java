package io.github.lightrag.storage;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;

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
        long sequence,
        SnapshotStatus snapshotStatus,
        SnapshotSource snapshotSource,
        Instant createdAt,
        String errorMessage
    ) {
        public DocumentGraphJournal {
            documentId = requireNonBlank(documentId, "documentId");
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
            snapshotStatus = Objects.requireNonNull(snapshotStatus, "snapshotStatus");
            snapshotSource = Objects.requireNonNull(snapshotSource, "snapshotSource");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            errorMessage = normalizeNullable(errorMessage);
        }
    }

    record ChunkGraphJournal(
        String documentId,
        String chunkId,
        int chunkOrder,
        String chunkHash,
        ChunkExtractStatus extractStatus,
        Instant createdAt,
        String errorMessage
    ) {
        public ChunkGraphJournal {
            documentId = requireNonBlank(documentId, "documentId");
            chunkId = requireNonBlank(chunkId, "chunkId");
            if (chunkOrder < 0) {
                throw new IllegalArgumentException("chunkOrder must not be negative");
            }
            chunkHash = requireNonBlank(chunkHash, "chunkHash");
            extractStatus = Objects.requireNonNull(extractStatus, "extractStatus");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
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
