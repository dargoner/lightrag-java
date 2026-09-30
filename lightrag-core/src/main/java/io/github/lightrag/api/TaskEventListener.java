package io.github.lightrag.api;

/**
 * Receives task lifecycle events for a single task or for the whole service.
 *
 * <h2>Callback reentrancy contract</h2>
 *
 * <p>All events except {@link TaskEventType#TASK_SUBMITTED} are dispatched synchronously, on the task thread, while
 * that task holds its workspace gate slot: stage / document / chunk / progress events and {@code TASK_RUNNING} /
 * {@code TASK_SUCCEEDED} / {@code TASK_CANCELLED} / {@code TASK_FAILED}. {@link TaskEventType#TASK_SUBMITTED} is the
 * exception — it is published by the submitting thread before the task enters the gate, so calling a workspace API
 * from that callback is equivalent to an ordinary caller.</p>
 *
 * <p>A listener must not call any workspace-level API ({@code ingest*}, {@code materialize*}, delete, rebuild,
 * {@code clearCache}, snapshots) from a callback, and must not block waiting for another task; callbacks exist for
 * recording, forwarding and reporting only. A violating call is refused deterministically before the gate work starts:
 * the gate logs the rejection with a stack trace and throws {@link IllegalStateException}; the gate work does not run
 * (the provider resolver and interrupted-task recovery that precede the gate still do) and the task itself continues,
 * because {@code TaskEventPublisher} isolates that exception. Nested acquisition of the same kind is not refused, but
 * is still forbidden usage: a document-scoped callback calling a document-scoped API runs inline without taking
 * another permit, and an exclusive callback calling an exclusive API is reentrant.</p>
 *
 * <p>The isolation covers {@link RuntimeException} only. A listener that throws an {@link Error} from a task event
 * propagates through the publisher and fails the task; {@code TASK_SUBMITTED} is that boundary's exception too — an
 * {@code Error} thrown from it reaches the {@code submit} caller directly.</p>
 *
 * @see io.github.lightrag.task.WorkspaceConcurrencyMode
 */
@FunctionalInterface
public interface TaskEventListener {
    void onEvent(TaskEvent event);
}
