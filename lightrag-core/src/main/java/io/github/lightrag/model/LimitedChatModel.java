package io.github.lightrag.model;

import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Takes one slot from its role's budget for the duration of each delegate call. {@code stream}
 * passes through unbounded: the returned iterator is consumed after this method returns, so the
 * slot window cannot cover the actual network read.
 */
final class LimitedChatModel implements ChatModel {
    private final Semaphore slots;
    private final ChatModel delegate;

    LimitedChatModel(Semaphore slots, ChatModel delegate) {
        this.slots = Objects.requireNonNull(slots, "slots");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public String generate(ChatRequest request) {
        acquire();
        try {
            return delegate.generate(request);
        } finally {
            slots.release();
        }
    }

    @Override
    public ChatResponse generateResponse(ChatRequest request) {
        acquire();
        try {
            return delegate.generateResponse(request);
        } finally {
            slots.release();
        }
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        return delegate.stream(request);
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
            throw new IllegalStateException("interrupted while waiting for an LLM slot", exception);
        }
    }
}
