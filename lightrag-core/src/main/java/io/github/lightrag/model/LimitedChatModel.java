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
        } catch (RuntimeException | Error failure) {
            slots.release();
            throw failure;
        }
        return new SlotReleasingIterator(stream);
    }

    private final class SlotReleasingIterator implements CloseableIterator<String> {
        private final CloseableIterator<String> iterator;
        private final AtomicBoolean released = new AtomicBoolean();
        private final Object closeLock = new Object();
        private boolean closeAttempted;
        private boolean closeInFlight;

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
            } catch (RuntimeException | Error failure) {
                closeDelegateQuietly();
                release();
                throw failure;
            }
        }

        @Override
        public String next() {
            try {
                return iterator.next();
            } catch (RuntimeException | Error failure) {
                closeDelegateQuietly();
                release();
                throw failure;
            }
        }

        @Override
        public void close() {
            try {
                synchronized (closeLock) {
                    if (!closeAttempted) {
                        closeAttempted = true;
                        closeInFlight = true;
                        try {
                            iterator.close();
                        } finally {
                            closeInFlight = false;
                        }
                    }
                }
            } finally {
                release();
            }
        }

        private void closeDelegateQuietly() {
            // The stream already reported completion or failed, so a failing close must not turn
            // that outcome into a different error; an explicit close() still propagates close
            // failures. The lock serialises every close attempt: a concurrent explicit close()
            // waits for an in-flight attempt instead of releasing the slot while the delegate may
            // still be open, and the delegate close runs at most once. While a close is in flight
            // (closeInFlight), reentrant callbacks from the delegate's own close() on the same
            // thread skip both the already-attempted close and the release: only the frame that
            // started the close releases, once iterator.close() has returned. Errors are
            // swallowed here too - the primary outcome must survive and the slot must never leak.
            synchronized (closeLock) {
                if (closeAttempted) {
                    return;
                }
                closeAttempted = true;
                closeInFlight = true;
                try {
                    iterator.close();
                } catch (Throwable ignored) {
                    // best effort on the exhausted or failed path
                } finally {
                    closeInFlight = false;
                }
            }
        }

        private void release() {
            synchronized (closeLock) {
                if (closeInFlight) {
                    // Reentrant call from inside the in-flight delegate close (same thread): the
                    // frame that started the close performs the release after it returns.
                    return;
                }
            }
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
