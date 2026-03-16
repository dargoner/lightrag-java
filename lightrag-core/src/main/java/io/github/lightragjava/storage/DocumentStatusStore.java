package io.github.lightragjava.storage;

import io.github.lightragjava.api.DocumentStatus;

import java.util.List;
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
        String errorMessage
    ) {
        public StatusRecord {
            documentId = requireNonBlank(documentId, "documentId");
            status = Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary.strip();
            errorMessage = errorMessage == null ? null : errorMessage.strip();
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
