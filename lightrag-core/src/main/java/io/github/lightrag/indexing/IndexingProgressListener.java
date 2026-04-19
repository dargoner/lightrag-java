package io.github.lightrag.indexing;

import io.github.lightrag.api.TaskStage;

public interface IndexingProgressListener {
    IndexingProgressListener NOOP = new IndexingProgressListener() {
    };

    default void onStageStarted(TaskStage stage, String message) {
    }

    default void onStageSucceeded(TaskStage stage, String message) {
    }

    default void onStageSkipped(TaskStage stage, String message) {
    }

    default void onDocumentStarted(String documentId, String message) {
    }

    default void onDocumentChunked(String documentId, int chunkCount, String message) {
    }

    default void onDocumentGraphReady(String documentId, int entityCount, int relationCount, String message) {
    }

    default void onDocumentVectorsReady(
        String documentId,
        int chunkVectorCount,
        int entityVectorCount,
        int relationVectorCount,
        String message
    ) {
    }

    default void onDocumentCommitted(String documentId, String message) {
    }

    default void onDocumentFailed(String documentId, String message) {
    }

    static IndexingProgressListener noop() {
        return NOOP;
    }
}
