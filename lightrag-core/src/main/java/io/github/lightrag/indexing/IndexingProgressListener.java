package io.github.lightrag.indexing;

import io.github.lightrag.api.TaskEventScope;
import io.github.lightrag.api.TaskStage;

/**
 * Progress callbacks for a running ingest task.
 *
 * <p>Every callback is dispatched synchronously, on the task thread, while the task holds its workspace gate slot, and
 * is subject to the same callback reentrancy contract as
 * {@link io.github.lightrag.api.TaskEventListener#onEvent(io.github.lightrag.api.TaskEvent)}: do not call any
 * workspace-level API and do not block waiting for another task from a callback. A violating call is refused with an
 * error log and an {@link IllegalStateException} before the gate work starts; the publisher isolates it and the task
 * continues. See {@code TaskEventListener} for the full contract, including the {@code TASK_SUBMITTED} boundary, which
 * this listener does not have.</p>
 */
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
