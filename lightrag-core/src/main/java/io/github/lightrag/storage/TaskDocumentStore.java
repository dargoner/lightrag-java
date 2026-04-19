package io.github.lightrag.storage;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.TaskDocumentSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface TaskDocumentStore {
    void save(TaskDocumentRecord record);

    Optional<TaskDocumentRecord> load(String taskId, String documentId);

    List<TaskDocumentRecord> listByTask(String taskId);

    void deleteByTask(String taskId);

    record TaskDocumentRecord(
        String taskId,
        String documentId,
        DocumentStatus status,
        int chunkCount,
        int entityCount,
        int relationCount,
        int chunkVectorCount,
        int entityVectorCount,
        int relationVectorCount,
        String errorMessage
    ) {
        public TaskDocumentRecord {
            taskId = requireNonBlank(taskId, "taskId");
            documentId = requireNonBlank(documentId, "documentId");
            status = Objects.requireNonNull(status, "status");
            errorMessage = errorMessage == null ? null : errorMessage.strip();
        }

        public TaskDocumentSnapshot toSnapshot() {
            return new TaskDocumentSnapshot(
                taskId,
                documentId,
                status,
                chunkCount,
                entityCount,
                relationCount,
                chunkVectorCount,
                entityVectorCount,
                relationVectorCount,
                errorMessage
            );
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
