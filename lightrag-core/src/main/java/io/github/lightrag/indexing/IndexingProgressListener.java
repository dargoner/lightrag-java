package io.github.lightrag.indexing;

import io.github.lightrag.api.TaskEventScope;
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

    default void onChunkPending(String documentId, String chunkId, TaskEventScope scope, String message) {
    }

    default void onChunkStarted(String documentId, String chunkId, String message) {
    }

    default void onChunkPrimaryExtracted(
        String documentId,
        String chunkId,
        int entityCount,
        int relationCount,
        String message
    ) {
    }

    default void onChunkGraphReady(String documentId, String chunkId, int entityCount, int relationCount, String message) {
    }

    default void onChunkVectorsReady(String documentId, String chunkId, int vectorCount, String message) {
    }

    default void onChunkSucceeded(String documentId, String chunkId, String message) {
    }

    default void onChunkFailed(String documentId, String chunkId, String message) {
    }

    static IndexingProgressListener noop() {
        return NOOP;
    }
}
