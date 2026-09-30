package io.github.lightrag.task;

/**
 * Concurrency mode a workspace-scoped operation declares to the workspace gate.
 *
 * <p>{@link #DOCUMENT_SCOPED} only states that the operation is isolated to one document or one chunk, so it may
 * overlap with other document-scoped operations. It does not relax any storage-level guarantee: every commit still
 * takes the provider write lock and the cross-process {@code StorageLockManager} lock.</p>
 */
public enum WorkspaceConcurrencyMode {
    /** Shares the workspace with other document-scoped operations, capped by {@code maxConcurrentDocumentTasks}. */
    DOCUMENT_SCOPED,
    /** Exclusive workspace access: nothing else in the workspace runs in parallel (restore and destructive flows). */
    WORKSPACE_EXCLUSIVE
}
