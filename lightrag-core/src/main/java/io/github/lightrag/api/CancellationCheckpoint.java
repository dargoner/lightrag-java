package io.github.lightrag.api;

/**
 * Cancellation checkpoint: the pipeline calls {@link #check()} at safe polling points, and an implementation throws
 * when cancellation has been requested, aborting the operation.
 *
 * <p>Contract:</p>
 * <ul>
 *   <li>Implementations must be <b>cheap and thread-safe</b>: the pipeline calls this from worker threads (the
 *       concurrently submitted extraction tasks).</li>
 *   <li>Implementations may only poll and throw: no blocking, no side effects. The thrown exception propagates out of
 *       {@code materializeDocumentGraph} unchanged, and the caller uses it to detect cancellation.</li>
 *   <li>Every polling point sits <b>outside the atomic commit</b>; once {@code writeAtomically} is entered the commit
 *       is not interruptible, so a cancellation arriving then means "the write commits in full and the task is marked
 *       cancelled" (the boundary semantics are spelled out in the class Javadoc of
 *       {@code GraphMaterializationPipeline}).</li>
 *   <li>{@link #NONE} is the no-op implementation; {@code null} is equivalent to it (the pipeline constructor
 *       normalizes both).</li>
 * </ul>
 */
@FunctionalInterface
public interface CancellationCheckpoint {
    CancellationCheckpoint NONE = () -> {
    };

    void check();
}
