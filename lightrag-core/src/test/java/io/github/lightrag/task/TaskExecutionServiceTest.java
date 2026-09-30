package io.github.lightrag.task;

import io.github.lightrag.api.TaskEvent;
import io.github.lightrag.api.TaskEventListener;
import io.github.lightrag.api.TaskEventType;
import io.github.lightrag.api.TaskSnapshot;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workspace gate semantics: {@code DOCUMENT_SCOPED} work shares the configured permit budget and
 * {@code WORKSPACE_EXCLUSIVE} work stays mutually exclusive with everything else in the workspace.
 *
 * <p>Concurrency assertions are synchronised with latches and bounded condition polls; no test decides a
 * winner by sleeping. Gate state is read through the package-private hooks on {@link TaskExecutionService}
 * so a leaked permit or ThreadLocal registration fails directly instead of surfacing as a hang.</p>
 */
class TaskExecutionServiceTest {
    private static final String WORKSPACE = "default";

    @Test
    void defaultModeStillSerializesTasksPerWorkspace() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var active = new AtomicInteger();
            var peak = new AtomicInteger();
            var firstStarted = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);

            var first = submitDocumentTask(service, () -> {
                var running = active.incrementAndGet();
                peak.accumulateAndGet(running, Math::max);
                firstStarted.countDown();
                await(releaseFirst);
                active.decrementAndGet();
            });
            await(firstStarted);
            assertThat(service.availableDocumentPermits(WORKSPACE)).isZero();

            var second = submitDocumentTask(service, () -> {
                var running = active.incrementAndGet();
                peak.accumulateAndGet(running, Math::max);
                active.decrementAndGet();
            });
            assertThat(service.availableDocumentPermits(WORKSPACE)).isZero();

            releaseFirst.countDown();
            assertThat(awaitTerminalTask(service, first).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, second).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(peak).hasValue(1);
            awaitDocumentPermits(service, 1);
        }
    }

    @Test
    void documentScopedTasksOverlapUpToConfiguredLimit() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage, List.of(), 2)) {
            var bothRunning = new CountDownLatch(2);
            var release = new CountDownLatch(1);

            var first = submitDocumentTask(service, () -> {
                bothRunning.countDown();
                await(release);
            });
            var second = submitDocumentTask(service, () -> {
                bothRunning.countDown();
                await(release);
            });

            assertThat(bothRunning.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.availableDocumentPermits(WORKSPACE)).isZero();

            release.countDown();
            assertThat(awaitTerminalTask(service, first).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, second).status()).isEqualTo(TaskStatus.SUCCEEDED);
            awaitDocumentPermits(service, 2);
        }
    }

    @Test
    void exclusiveTaskWaitsForRunningDocumentScopedTasks() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var order = new CopyOnWriteArrayList<String>();
            var doc1Started = new CountDownLatch(1);
            var releaseDoc1 = new CountDownLatch(1);

            var doc1 = submitDocumentTask(service, () -> {
                order.add("doc1");
                doc1Started.countDown();
                await(releaseDoc1);
            });
            await(doc1Started);

            var exclusive = submitExclusiveTask(service, () -> order.add("exclusive"));
            awaitCondition("exclusive task parks on the write lock", () -> service.queuedGateWaiters(WORKSPACE) >= 1);
            assertThat(order).doesNotContain("exclusive");

            releaseDoc1.countDown();
            var doc2 = submitDocumentTask(service, () -> order.add("doc2"));

            assertThat(awaitTerminalTask(service, doc1).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, exclusive).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, doc2).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(order).containsExactly("doc1", "exclusive", "doc2");
        }
    }

    @Test
    void documentScopedTaskWaitsForQueuedExclusiveTask() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var order = new CopyOnWriteArrayList<String>();
            var doc1Started = new CountDownLatch(1);
            var releaseDoc1 = new CountDownLatch(1);

            var doc1 = submitDocumentTask(service, () -> {
                order.add("doc1");
                doc1Started.countDown();
                await(releaseDoc1);
            });
            await(doc1Started);

            var exclusive = submitExclusiveTask(service, () -> order.add("exclusive"));
            awaitCondition("exclusive task parks on the write lock", () -> service.queuedGateWaiters(WORKSPACE) >= 1);

            var doc2 = submitDocumentTask(service, () -> order.add("doc2"));
            awaitCondition("document task parks on the permit", () -> service.queuedDocumentPermitWaiters(WORKSPACE) >= 1);
            assertThat(order).doesNotContain("doc2");

            releaseDoc1.countDown();
            assertThat(awaitTerminalTask(service, doc1).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, exclusive).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, doc2).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(order).containsExactly("doc1", "exclusive", "doc2");
        }
    }

    @Test
    void nestedDocumentScopedAcquisitionDoesNotDeadlock() {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var nestedRan = new AtomicBoolean();
            var permitsInsideNested = new AtomicInteger(-1);

            var startedAt = Instant.now();
            String result = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider ->
                service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, nested -> {
                    permitsInsideNested.set(service.availableDocumentPermits(WORKSPACE));
                    nestedRan.set(true);
                    return "nested";
                })
            );

            assertThat(result).isEqualTo("nested");
            assertThat(nestedRan).isTrue();
            assertThat(permitsInsideNested).hasValue(0);
            assertThat(Duration.between(startedAt, Instant.now())).isLessThan(Duration.ofSeconds(1));
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
            assertThat(service.currentThreadHoldsDocumentSlot(WORKSPACE)).isFalse();
        }
    }

    @Test
    void rejectsExclusiveAcquisitionFromThreadHoldingDocumentSlot() {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            assertThatThrownBy(() -> service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider ->
                service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE, exclusive -> "never")
            ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workspace-exclusive work cannot start from a thread that already holds a document-scoped slot");

            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
            assertThat(service.currentThreadHoldsDocumentSlot(WORKSPACE)).isFalse();
            assertThat(service.currentThreadHoldsExclusiveSlot(WORKSPACE)).isFalse();
        }
    }

    @Test
    void runInWorkspaceHonoursTheSameGateAsSubmit() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage, List.of(), 2)) {
            var syncHolds = new CountDownLatch(1);
            var releaseSync = new CountDownLatch(1);
            var threadFailure = new AtomicReference<Throwable>();
            var syncThread = startThread("sync-gate-entry", () -> service.runInWorkspace(
                WORKSPACE,
                WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
                provider -> {
                    syncHolds.countDown();
                    await(releaseSync);
                    return null;
                }
            ), threadFailure);
            await(syncHolds);
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);

            var taskStarted = new CountDownLatch(1);
            var releaseTask = new CountDownLatch(1);
            var taskId = submitDocumentTask(service, () -> {
                taskStarted.countDown();
                await(releaseTask);
            });
            assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.availableDocumentPermits(WORKSPACE)).isZero();

            releaseSync.countDown();
            syncThread.join(TimeUnit.SECONDS.toMillis(2));
            assertThat(syncThread.isAlive()).isFalse();
            assertThat(threadFailure.get()).isNull();
            releaseTask.countDown();
            assertThat(awaitTerminalTask(service, taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
            awaitDocumentPermits(service, 2);
        }
    }

    @Test
    void cancellingWhileWaitingForPermitHoldsNothingAndBlocksNobody() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var firstStarted = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var first = submitDocumentTask(service, () -> {
                firstStarted.countDown();
                await(releaseFirst);
            });
            await(firstStarted);

            var secondRan = new AtomicBoolean();
            var second = submitDocumentTask(service, () -> secondRan.set(true));
            awaitCondition("second task parks on the permit", () -> service.queuedDocumentPermitWaiters(WORKSPACE) >= 1);

            var cancelled = service.cancel(WORKSPACE, second);
            assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);

            releaseFirst.countDown();
            assertThat(awaitTerminalTask(service, first).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, second).status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(secondRan).isFalse();

            var third = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "third");
            assertThat(third).isEqualTo("third");
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
        }
    }

    @Test
    void cancellingWhileWaitingForReadLockReleasesThePermit() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var exclusiveStarted = new CountDownLatch(1);
            var releaseExclusive = new CountDownLatch(1);
            var exclusive = submitExclusiveTask(service, () -> {
                exclusiveStarted.countDown();
                await(releaseExclusive);
            });
            await(exclusiveStarted);

            var documentRan = new AtomicBoolean();
            var documentTask = submitDocumentTask(service, () -> documentRan.set(true));
            awaitCondition("document task holds the single permit", () -> service.availableDocumentPermits(WORKSPACE) == 0);

            service.cancel(WORKSPACE, documentTask);
            awaitCondition("permit released by the interrupted lock acquisition", () -> service.availableDocumentPermits(WORKSPACE) == 1);
            assertThat(documentRan).isFalse();

            releaseExclusive.countDown();
            assertThat(awaitTerminalTask(service, exclusive).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, documentTask).status()).isEqualTo(TaskStatus.CANCELLED);
            awaitDocumentPermits(service, 1);
        }
    }

    @Test
    void cancellingAQueuedTaskLeavesNoLockOrThreadLocal() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var holderStarted = new CountDownLatch(1);
            var releaseHolder = new CountDownLatch(1);
            var holder = submitExclusiveTask(service, () -> {
                holderStarted.countDown();
                await(releaseHolder);
            });
            await(holderStarted);

            var queuedRan = new AtomicBoolean();
            var queued = submitExclusiveTask(service, () -> queuedRan.set(true));
            awaitCondition("queued exclusive task parks on the write lock", () -> service.queuedGateWaiters(WORKSPACE) >= 1);

            service.cancel(WORKSPACE, queued);
            releaseHolder.countDown();

            assertThat(awaitTerminalTask(service, holder).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, queued).status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(queuedRan).isFalse();

            var laterExclusive = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE, provider -> "exclusive-ok");
            assertThat(laterExclusive).isEqualTo("exclusive-ok");
            var laterDocument = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "document-ok");
            assertThat(laterDocument).isEqualTo("document-ok");
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
            assertThat(service.currentThreadHoldsExclusiveSlot(WORKSPACE)).isFalse();
            assertThat(service.currentThreadHoldsDocumentSlot(WORKSPACE)).isFalse();
        }
    }

    @Test
    void releasesPermitWhenWorkThrows() throws Exception {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            var failing = submitDocumentTask(service, () -> {
                throw new IllegalStateException("work boom");
            });
            assertThat(awaitTerminalTask(service, failing).status()).isEqualTo(TaskStatus.FAILED);

            var later = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "later");
            assertThat(later).isEqualTo("later");
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
        }
    }

    @Test
    void releasesPermitWhenNestedCallThrows() {
        var storage = InMemoryStorageProvider.create();
        try (var service = new TaskExecutionService(workspace -> storage)) {
            assertThatThrownBy(() -> service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider ->
                service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, nested -> {
                    throw new IllegalArgumentException("nested boom");
                })
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nested boom");

            assertThat(service.<String>runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "first")).isEqualTo("first");
            assertThat(service.<String>runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "second")).isEqualTo("second");
            assertThat(service.availableDocumentPermits(WORKSPACE)).isEqualTo(1);
            assertThat(service.currentThreadHoldsDocumentSlot(WORKSPACE)).isFalse();
        }
    }

    @Test
    void doesNotOccupyAnythingWhenProviderResolverThrows() {
        var failure = new IllegalStateException("resolver boom");
        try (var service = new TaskExecutionService(workspace -> {
            throw failure;
        })) {
            var workRan = new AtomicBoolean();
            assertThatThrownBy(() -> service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> {
                workRan.set(true);
                return null;
            })).isSameAs(failure);

            assertThat(workRan).isFalse();
            assertThat(service.workspaceGateCount()).isZero();
        }
    }

    @Test
    void listenerReenteringExclusiveApiIsRefusedWithoutFailingTheTask() throws Exception {
        var storage = InMemoryStorageProvider.create();
        var refused = new AtomicBoolean();
        var exclusiveRan = new AtomicBoolean();
        var serviceRef = new AtomicReference<TaskExecutionService>();
        TaskEventListener listener = event -> {
            if (event.eventType() == TaskEventType.TASK_RUNNING) {
                try {
                    serviceRef.get().runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE, provider -> {
                        exclusiveRan.set(true);
                        return null;
                    });
                } catch (IllegalStateException exception) {
                    refused.set(true);
                    // Rethrown on purpose: the publisher isolation path must be part of the scenario.
                    throw exception;
                }
            }
        };

        try (var service = new TaskExecutionService(workspace -> storage, List.of(listener), 1)) {
            serviceRef.set(service);
            var taskId = submitDocumentTask(service, () -> {
            });

            assertThat(awaitTerminalTask(service, taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(refused).isTrue();
            assertThat(exclusiveRan).isFalse();
            awaitDocumentPermits(service, 1);

            var later = service.runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> "later");
            assertThat(later).isEqualTo("later");
        }
    }

    @Test
    void rejectsDocumentScopedAcquisitionFromThreadHoldingExclusiveSlot() throws Exception {
        var storage = InMemoryStorageProvider.create();
        var refused = new AtomicBoolean();
        var listenerRan = new AtomicBoolean();
        var serviceRef = new AtomicReference<TaskExecutionService>();
        TaskEventListener listener = event -> {
            if (event.eventType() == TaskEventType.DOCUMENT_STARTED) {
                try {
                    serviceRef.get().runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> {
                        listenerRan.set(true);
                        return null;
                    });
                } catch (IllegalStateException exception) {
                    refused.set(true);
                    throw exception;
                }
            }
        };

        try (var service = new TaskExecutionService(workspace -> storage, List.of(listener), 1)) {
            serviceRef.set(service);
            var exclusiveStarted = new CountDownLatch(1);
            var documentRan = new AtomicBoolean();

            // The exclusive task fires the callback only once a document task has parked on the read lock:
            // that is the interlock the gate must refuse instead of deadlocking on the single permit.
            var gatedExclusive = service.submit(
                WORKSPACE,
                TaskType.DELETE_DOCUMENT,
                Map.of(),
                WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE,
                List.of(),
                progress -> {
                    exclusiveStarted.countDown();
                    awaitCondition("document task parks on the read lock", () -> service.availableDocumentPermits(WORKSPACE) == 0);
                    progress.onDocumentStarted("doc-probe", "probe");
                }
            );
            await(exclusiveStarted);

            var documentTask = submitDocumentTask(service, () -> documentRan.set(true));
            awaitCondition("document task holds the single permit", () -> service.availableDocumentPermits(WORKSPACE) == 0);

            assertThat(awaitTerminalTask(service, gatedExclusive).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(awaitTerminalTask(service, documentTask).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(refused).isTrue();
            assertThat(listenerRan).isFalse();
            assertThat(documentRan).isTrue();
            awaitDocumentPermits(service, 1);
        }
    }

    @Test
    void listenerReenteringDocumentScopedApiRunsInlineWithoutExtraPermit() throws Exception {
        var storage = InMemoryStorageProvider.create();
        var nestedRan = new AtomicBoolean();
        var permitsInsideNested = new AtomicInteger(-1);
        var serviceRef = new AtomicReference<TaskExecutionService>();
        TaskEventListener listener = event -> {
            if (event.eventType() == TaskEventType.TASK_RUNNING) {
                serviceRef.get().runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> {
                    permitsInsideNested.set(serviceRef.get().availableDocumentPermits(WORKSPACE));
                    nestedRan.set(true);
                    return null;
                });
            }
        };

        try (var service = new TaskExecutionService(workspace -> storage, List.of(listener), 1)) {
            serviceRef.set(service);
            var taskId = submitDocumentTask(service, () -> {
            });

            assertThat(awaitTerminalTask(service, taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
            assertThat(nestedRan).isTrue();
            assertThat(permitsInsideNested).hasValue(0);
            awaitDocumentPermits(service, 1);
        }
    }

    @Test
    void listenerCallingWorkspaceApiFromSubmittedCallbackIsNotRefused() throws Exception {
        var storage = InMemoryStorageProvider.create();
        var markers = new CopyOnWriteArrayList<String>();
        var callbackThread = new AtomicReference<Thread>();
        var serviceRef = new AtomicReference<TaskExecutionService>();
        TaskEventListener listener = event -> {
            if (event.eventType() == TaskEventType.TASK_SUBMITTED) {
                callbackThread.set(Thread.currentThread());
                serviceRef.get().runInWorkspace(WORKSPACE, WorkspaceConcurrencyMode.DOCUMENT_SCOPED, provider -> {
                    markers.add("submitted-callback");
                    return null;
                });
            }
        };

        try (var service = new TaskExecutionService(workspace -> storage, List.of(listener), 1)) {
            serviceRef.set(service);
            var submitThread = Thread.currentThread();
            var taskId = submitDocumentTask(service, () -> {
            });

            assertThat(callbackThread).hasValue(submitThread);
            assertThat(markers).containsExactly("submitted-callback");
            assertThat(awaitTerminalTask(service, taskId).status()).isEqualTo(TaskStatus.SUCCEEDED);
            awaitDocumentPermits(service, 1);
        }
    }

    @Test
    void listenerThrowingErrorFailsTheTask() throws Exception {
        var storage = InMemoryStorageProvider.create();
        TaskEventListener listener = event -> {
            if (event.eventType() == TaskEventType.TASK_RUNNING) {
                throw new AssertionError("listener error");
            }
        };

        try (var service = new TaskExecutionService(workspace -> storage, List.of(listener), 1)) {
            var taskId = submitDocumentTask(service, () -> {
            });

            assertThat(awaitTerminalTask(service, taskId).status()).isEqualTo(TaskStatus.FAILED);
            awaitDocumentPermits(service, 1);
        }
    }

    private static String submitDocumentTask(TaskExecutionService service, Runnable work) {
        return service.submit(
            WORKSPACE,
            TaskType.INGEST_DOCUMENTS,
            Map.of(),
            WorkspaceConcurrencyMode.DOCUMENT_SCOPED,
            List.of(),
            progress -> work.run()
        );
    }

    private static String submitExclusiveTask(TaskExecutionService service, Runnable work) {
        return service.submit(
            WORKSPACE,
            TaskType.DELETE_DOCUMENT,
            Map.of(),
            WorkspaceConcurrencyMode.WORKSPACE_EXCLUSIVE,
            List.of(),
            progress -> work.run()
        );
    }

    private static TaskSnapshot awaitTerminalTask(TaskExecutionService service, String taskId) {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        TaskSnapshot snapshot = service.getTask(WORKSPACE, taskId);
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            snapshot = service.getTask(WORKSPACE, taskId);
        }
        assertThat(snapshot.status().isTerminal()).as("task %s reaches a terminal status", taskId).isTrue();
        return snapshot;
    }

    // A terminal task record is written inside the gate, so the permit is released slightly after
    // awaitTerminalTask returns; wait for the count instead of reading it once.
    private static void awaitDocumentPermits(TaskExecutionService service, int expected) {
        awaitCondition(
            "document permits settle at " + expected,
            () -> service.availableDocumentPermits(WORKSPACE) == expected
        );
    }

    private static void awaitCondition(String description, BooleanSupplier condition) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for: " + description);
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Test interrupted", exception);
        }
    }

    private static Thread startThread(String name, Runnable body, AtomicReference<Throwable> failure) {
        var thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
