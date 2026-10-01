package io.github.lightrag.model;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes one slot from its role's budget for the duration of each delegate call. A stream keeps its
 * slot until the returned iterator is exhausted, closed or fails, so open provider connections
 * count against the role budget too.
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
    public String cacheIdentity() {
        return delegate.cacheIdentity();
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        acquire();
        CloseableIterator<String> stream;
        try {
            stream = delegate.stream(request);
        } catch (RuntimeException exception) {
            slots.release();
            throw exception;
        }
        return new SlotReleasingIterator(stream);
    }

    private final class SlotReleasingIterator implements CloseableIterator<String> {
        private final CloseableIterator<String> iterator;
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean delegateClosed = new AtomicBoolean();

        private SlotReleasingIterator(CloseableIterator<String> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            try {
                var hasNext = iterator.hasNext();
                if (!hasNext) {
                    closeDelegateQuietly();
                    release();
                }
                return hasNext;
            } catch (RuntimeException exception) {
                closeDelegateQuietly();
                release();
                throw exception;
            }
        }

        @Override
        public String next() {
            try {
                return iterator.next();
            } catch (RuntimeException exception) {
                closeDelegateQuietly();
                release();
                throw exception;
            }
        }

        @Override
        public void close() {
            try {
                if (delegateClosed.compareAndSet(false, true)) {
                    iterator.close();
                }
            } finally {
                release();
            }
        }

        private void closeDelegateQuietly() {
            // The stream already reported completion or failed, so a failing close must not turn
            // that outcome into a different error; an explicit close() still propagates close
            // failures. Closing before releasing keeps the slot held while the underlying
            // resource may still be open.
            if (delegateClosed.compareAndSet(false, true)) {
                try {
                    iterator.close();
                } catch (RuntimeException ignored) {
                    // best effort on the exhausted or failed path
                }
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
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
            throw new IllegalStateException("interrupted while waiting for an LLM slot", exception);
        }
    }
}
