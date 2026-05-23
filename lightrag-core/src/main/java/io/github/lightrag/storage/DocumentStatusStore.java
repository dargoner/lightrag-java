package io.github.lightrag.storage;

import io.github.lightrag.api.DocumentStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface DocumentStatusStore {
    void save(StatusRecord statusRecord);

    Optional<StatusRecord> load(String documentId);

    List<StatusRecord> list();

    void delete(String documentId);

    record StatusRecord(
        String documentId,
        DocumentStatus status,
        String summary,
        String errorMessage,
        Map<String, Object> metadata
    ) {
        public StatusRecord(String documentId, DocumentStatus status, String summary, String errorMessage) {
            this(documentId, status, summary, errorMessage, Map.of());
        }

        public StatusRecord {
            documentId = requireNonBlank(documentId, "documentId");
            status = Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary.strip();
            errorMessage = errorMessage == null ? null : errorMessage.strip();
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        }

        private static String requireNonBlank(String value, String fieldName) {
            Objects.requireNonNull(value, fieldName);
            var normalized = value.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return normalized;
        }
    }
}
