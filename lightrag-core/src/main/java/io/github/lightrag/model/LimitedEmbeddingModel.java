package io.github.lightrag.model;

import java.util.List;
import java.util.Objects;

/**
 * Takes one slot from the shared embedding budget for the duration of each {@code embedAll} call.
 * Queued calls are woken by {@link LlmConcurrencyBudget.EmbeddingPriority} first, arrival order
 * second.
 */
final class LimitedEmbeddingModel implements EmbeddingModel {
    private final PrioritySemaphore slots;
    private final LlmConcurrencyBudget.EmbeddingPriority priority;
    private final EmbeddingModel delegate;

    LimitedEmbeddingModel(
        PrioritySemaphore slots,
        LlmConcurrencyBudget.EmbeddingPriority priority,
        EmbeddingModel delegate
    ) {
        this.slots = Objects.requireNonNull(slots, "slots");
        this.priority = Objects.requireNonNull(priority, "priority");
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
        if (slots.tryAcquire(priority)) {
            return;
        }
        slots.acquire(priority);
    }
}
