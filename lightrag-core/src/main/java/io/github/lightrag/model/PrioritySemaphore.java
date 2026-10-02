package io.github.lightrag.model;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Slot pool whose waiters are served in (priority, arrival) order, mirroring the upstream embedding
 * queue ({@code asyncio.PriorityQueue}, {@code constants.py:671-673}: lower priority value runs
 * first; query-time embeddings at 5 vs ingestion at 10, {@code operate.py:5347-5349}).
 *
 * <p>Keeps the contract of the fair semaphore it replaces: one slot per delegate call, no
 * reentrancy, an uncontended acquisition never consults the interrupt status, and a queued thread
 * aborts on interruption with the flag restored.
 */
final class PrioritySemaphore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final PriorityQueue<Waiter> waiters = new PriorityQueue<>(
        Comparator.comparingInt((Waiter waiter) -> waiter.priority.ordinal())
            .thenComparingLong(waiter -> waiter.sequence)
    );
    private int permits;
    private long sequence;

    PrioritySemaphore(int permits) {
        this.permits = permits;
    }

    boolean tryAcquire(LlmConcurrencyBudget.EmbeddingPriority priority) {
        lock.lock();
        try {
            if (permits > 0) {
                permits--;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    void acquire(LlmConcurrencyBudget.EmbeddingPriority priority) {
        lock.lock();
        try {
            if (permits > 0) {
                permits--;
                return;
            }
            var waiter = new Waiter(priority, sequence++);
            waiters.add(waiter);
            while (!waiter.granted) {
                try {
                    available.await();
                } catch (InterruptedException exception) {
                    if (waiter.granted) {
                        // A release raced the interruption: we already own a slot, so pass it on.
                        handOffOrReturnPermit();
                    } else {
                        waiters.remove(waiter);
                    }
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for an embedding slot", exception);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    void release() {
        lock.lock();
        try {
            handOffOrReturnPermit();
        } finally {
            lock.unlock();
        }
    }

    private void handOffOrReturnPermit() {
        var next = waiters.poll();
        if (next == null) {
            permits++;
            return;
        }
        next.granted = true;
        // All waiters share one condition; only the granted waiter may proceed, the others re-check
        // their own flag, so every waiter must be woken.
        available.signalAll();
    }

    private static final class Waiter {
        private final LlmConcurrencyBudget.EmbeddingPriority priority;
        private final long sequence;
        private boolean granted;

        private Waiter(LlmConcurrencyBudget.EmbeddingPriority priority, long sequence) {
            this.priority = priority;
            this.sequence = sequence;
        }
    }
}
