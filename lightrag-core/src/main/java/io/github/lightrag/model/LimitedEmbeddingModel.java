package io.github.lightrag.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Takes one slot from the shared embedding budget for the duration of each {@code embedAll} call. */
final class LimitedEmbeddingModel implements EmbeddingModel {
    private final Semaphore slots;
    private final EmbeddingModel delegate;

    LimitedEmbeddingModel(Semaphore slots, EmbeddingModel delegate) {
        this.slots = Objects.requireNonNull(slots, "slots");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String cacheIdentity() {
        return delegate.cacheIdentity();
    }

    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        acquire();
        try {
            return delegate.embedAll(texts);
        } finally {
            slots.release();
        }
    }

    private void acquire() {
        // tryAcquire keeps a pending cancellation flag from failing an uncontended call: the budget
        // orders calls, it does not decide cancellation - that stays the delegate's business.
        if (slots.tryAcquire()) {
            return;
        }
        try {
            slots.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an embedding slot", exception);
        }
    }
}
