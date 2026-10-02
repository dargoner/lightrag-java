package io.github.lightrag.model;

import io.github.lightrag.model.LlmConcurrencyBudget.EmbeddingPriority;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmConcurrencyBudgetTest {

    @Test
    void capsConcurrentChatCallsPerRole() throws Exception {
        var budget = new LlmConcurrencyBudget(2, 8);
        var delegate = new ConcurrencyRecordingChatModel();
        var model = budget.limitChat("extract", delegate);
        var threads = new ArrayList<Thread>();
        for (var index = 0; index < 8; index++) {
            var callIndex = index;
            var thread = new Thread(() -> {
                if (callIndex % 2 == 0) {
                    model.generate(request("prompt-" + callIndex));
                } else {
                    model.generateResponse(request("prompt-" + callIndex));
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (var thread : threads) {
            thread.join();
        }

        assertThat(delegate.peakConcurrency()).isEqualTo(2);
        assertThat(delegate.calls()).isEqualTo(8);
    }

    @Test
    void limitChatForwardsTheDelegateCacheIdentity() {
        var budget = new LlmConcurrencyBudget(2, 8);
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatModel.ChatRequest request) {
                return "answered";
            }

            @Override
            public String cacheIdentity() {
                return "openai-compatible:gpt-4o-mini@https://api.example/v1";
            }
        });

        assertThat(model.cacheIdentity()).isEqualTo("openai-compatible:gpt-4o-mini@https://api.example/v1");
    }

    @Test
    void distinctRolesDoNotShareLlmSlots() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var extractEntered = new CountDownLatch(1);
        var releaseExtract = new CountDownLatch(1);
        var extract = budget.limitChat("extract", (ChatModel) request -> {
            extractEntered.countDown();
            awaitQuietly(releaseExtract);
            return "extracted";
        });
        var query = budget.limitChat("query", (ChatModel) request -> "answered");

        var extractThread = new Thread(() -> extract.generate(request("extract")));
        extractThread.start();
        assertThat(extractEntered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var answered = CompletableFuture.supplyAsync(() -> query.generate(request("query")));
            assertThat(answered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");
        } finally {
            releaseExtract.countDown();
            extractThread.join();
        }
    }

    @Test
    void capsConcurrentEmbeddingCalls() throws Exception {
        var budget = new LlmConcurrencyBudget(4, 2);
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var calls = new AtomicInteger();
        var model = budget.limitEmbedding(texts -> {
            calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
                return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        });

        var threads = new ArrayList<Thread>();
        for (var index = 0; index < 6; index++) {
            var thread = new Thread(() -> model.embedAll(List.of("text")));
            thread.start();
            threads.add(thread);
        }
        for (var thread : threads) {
            thread.join();
        }

        assertThat(peak).hasValue(2);
        assertThat(calls).hasValue(6);
    }

    @Test
    void highPriorityEmbeddingsJumpAheadOfQueuedLowPriorityWaiters() throws Exception {
        // Mirrors upstream: query-time embedding requests (priority 5) overtake queued ingestion
        // requests (priority 10) in the shared embedding queue (operate.py:5347-5349).
        var budget = new LlmConcurrencyBudget(4, 1);
        var order = new CopyOnWriteArrayList<String>();
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            order.add("holder");
            return vectors(texts);
        });
        var low = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            order.add("low");
            return vectors(texts);
        });
        var high = budget.limitEmbedding(EmbeddingPriority.HIGH, texts -> {
            order.add("high");
            return vectors(texts);
        });

        var holderThread = new Thread(() -> holder.embedAll(List.of("text")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var lowThread = new Thread(() -> low.embedAll(List.of("text")));
        lowThread.start();
        awaitParked(lowThread);
        var highThread = new Thread(() -> high.embedAll(List.of("text")));
        highThread.start();
        awaitParked(highThread);
        try {
            releaseHolder.countDown();
            holderThread.join(5_000L);
            lowThread.join(5_000L);
            highThread.join(5_000L);
            assertThat(holderThread.isAlive()).isFalse();
            assertThat(lowThread.isAlive()).isFalse();
            assertThat(highThread.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
        }

        assertThat(order).containsExactly("holder", "high", "low");
    }

    @Test
    void priorityQueueUnderConcurrencyNeverLosesAWakeup() throws Exception {
        var budget = new LlmConcurrencyBudget(4, 2);
        var calls = new AtomicInteger();
        var peak = new AtomicInteger();
        var active = new AtomicInteger();
        var recorder = (EmbeddingModel) texts -> {
            calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(1L);
                return vectors(texts);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        };
        var high = budget.limitEmbedding(EmbeddingPriority.HIGH, recorder);
        var low = budget.limitEmbedding(EmbeddingPriority.LOW, recorder);

        var threads = new ArrayList<Thread>();
        for (var index = 0; index < 8; index++) {
            var callIndex = index;
            var thread = new Thread(() -> {
                for (var round = 0; round < 5; round++) {
                    if ((callIndex + round) % 2 == 0) {
                        high.embedAll(List.of("text"));
                    } else {
                        low.embedAll(List.of("text"));
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        for (var thread : threads) {
            thread.join(10_000L);
            assertThat(thread.isAlive()).isFalse();
        }

        assertThat(calls).hasValue(40);
        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void samePriorityEmbeddingsKeepTheirArrivalOrder() throws Exception {
        var budget = new LlmConcurrencyBudget(4, 1);
        var order = new CopyOnWriteArrayList<String>();
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            order.add("holder");
            return vectors(texts);
        });
        var first = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            order.add("first");
            return vectors(texts);
        });
        var second = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            order.add("second");
            return vectors(texts);
        });

        var holderThread = new Thread(() -> holder.embedAll(List.of("text")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var firstThread = new Thread(() -> first.embedAll(List.of("text")));
        firstThread.start();
        awaitParked(firstThread);
        var secondThread = new Thread(() -> second.embedAll(List.of("text")));
        secondThread.start();
        awaitParked(secondThread);
        try {
            releaseHolder.countDown();
            holderThread.join(5_000L);
            firstThread.join(5_000L);
            secondThread.join(5_000L);
            assertThat(holderThread.isAlive()).isFalse();
            assertThat(firstThread.isAlive()).isFalse();
            assertThat(secondThread.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
        }

        assertThat(order).containsExactly("holder", "first", "second");
    }

    @Test
    void legacyLimitEmbeddingOverloadQueuesAtLowPriority() throws Exception {
        var budget = new LlmConcurrencyBudget(4, 1);
        var order = new CopyOnWriteArrayList<String>();
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitEmbedding(EmbeddingPriority.HIGH, texts -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            order.add("holder");
            return vectors(texts);
        });
        var legacy = budget.limitEmbedding(texts -> {
            order.add("legacy");
            return vectors(texts);
        });
        var high = budget.limitEmbedding(EmbeddingPriority.HIGH, texts -> {
            order.add("high");
            return vectors(texts);
        });

        var holderThread = new Thread(() -> holder.embedAll(List.of("text")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var legacyThread = new Thread(() -> legacy.embedAll(List.of("text")));
        legacyThread.start();
        awaitParked(legacyThread);
        var highThread = new Thread(() -> high.embedAll(List.of("text")));
        highThread.start();
        awaitParked(highThread);
        try {
            releaseHolder.countDown();
            holderThread.join(5_000L);
            legacyThread.join(5_000L);
            highThread.join(5_000L);
            assertThat(holderThread.isAlive()).isFalse();
            assertThat(legacyThread.isAlive()).isFalse();
            assertThat(highThread.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
        }

        assertThat(order).containsExactly("holder", "high", "legacy");
    }

    @Test
    void interruptedEmbeddingWaitersAbortWithoutConsumingTheSlot() throws Exception {
        var budget = new LlmConcurrencyBudget(4, 1);
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            return vectors(texts);
        });
        var waiter = budget.limitEmbedding(EmbeddingPriority.LOW, texts -> vectors(texts));

        var holderThread = new Thread(() -> holder.embedAll(List.of("text")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var failure = new AtomicReference<RuntimeException>();
        var interruptFlagRestored = new AtomicInteger();
        var waiterThread = new Thread(() -> {
            try {
                waiter.embedAll(List.of("text"));
            } catch (RuntimeException exception) {
                failure.set(exception);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted() ? 1 : 0);
            }
        });
        waiterThread.start();
        awaitParked(waiterThread);
        waiterThread.interrupt();
        waiterThread.join(5_000L);
        try {
            assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted while waiting for an embedding slot");
            assertThat(interruptFlagRestored).hasValue(1);

            // The aborted waiter must not have swallowed a permit: after the holder leaves, a
            // fresh acquisition still completes.
            releaseHolder.countDown();
            holderThread.join(5_000L);
            var recovered = CompletableFuture.supplyAsync(
                () -> budget.limitEmbedding(EmbeddingPriority.HIGH, texts -> vectors(texts))
                    .embedAll(List.of("text")));
            assertThat(recovered.get(5, TimeUnit.SECONDS)).hasSize(1);
        } finally {
            releaseHolder.countDown();
            holderThread.join(5_000L);
        }
    }

    @Test
    void streamedResponsesHoldTheirSlotUntilTheIteratorIsExhausted() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var streamer = budget.limitChat("query", (ChatModel) request -> "streamed");
        var generator = budget.limitChat("query", (ChatModel) request -> "answered");

        try (var stream = streamer.stream(request("stream"))) {
            assertThat(stream.next()).isEqualTo("streamed");
            var blocked = new Thread(() -> generator.generate(request("answer")));
            blocked.start();
            awaitParked(blocked);
            assertThat(stream.hasNext()).isFalse();
            blocked.join(5_000L);
            assertThat(blocked.isAlive()).isFalse();
        }
    }

    @Test
    void closingAStreamEarlyReleasesItsSlot() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var streamer = budget.limitChat("query", (ChatModel) request -> "streamed");
        var generator = budget.limitChat("query", (ChatModel) request -> "answered");

        var stream = streamer.stream(request("stream"));
        var blocked = new Thread(() -> generator.generate(request("answer")));
        blocked.start();
        awaitParked(blocked);
        stream.close();
        blocked.join(5_000L);
        assertThat(blocked.isAlive()).isFalse();
    }

    @Test
    void exhaustedStreamsCloseTheDelegateIteratorExactlyOnce() {
        var budget = new LlmConcurrencyBudget(1, 8);
        var closed = new AtomicInteger();
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    private boolean consumed;

                    @Override
                    public boolean hasNext() {
                        return !consumed;
                    }

                    @Override
                    public String next() {
                        consumed = true;
                        return "chunk";
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThat(stream.next()).isEqualTo("chunk");
        assertThat(stream.hasNext()).isFalse();
        assertThat(closed).hasValue(1);

        stream.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void hasNextFailureClosesTheDelegateIteratorWithoutMaskingTheOriginalError() {
        var closed = new AtomicInteger();
        var budget = new LlmConcurrencyBudget(1, 8);
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new IllegalStateException("stream broken");
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        throw new IllegalStateException("provider close failed");
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThatThrownBy(stream::hasNext)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stream broken");
        assertThat(closed).hasValue(1);

        stream.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void nextFailureClosesTheDelegateIteratorAndReleasesItsSlot() throws Exception {
        var closed = new AtomicInteger();
        var budget = new LlmConcurrencyBudget(1, 8);
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public String next() {
                        throw new IllegalStateException("chunk failed");
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThatThrownBy(stream::next)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("chunk failed");
        assertThat(closed).hasValue(1);

        stream.close();
        assertThat(closed).hasValue(1);

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");
    }

    @Test
    void exhaustionClosesTheDelegateBeforeReleasingItsSlot() {
        var closeCalled = new AtomicBoolean();
        var slotReleasedAfterClose = new AtomicBoolean();
        // release() runs only from the SlotReleasingIterator, so recording the delegate state at
        // that moment pins the close-before-release order (review round 3, nit).
        var slots = new Semaphore(1) {
            @Override
            public void release() {
                slotReleasedAfterClose.set(closeCalled.get());
                super.release();
            }
        };
        var model = new LimitedChatModel(slots, new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        closeCalled.set(true);
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThat(stream.hasNext()).isFalse();
        assertThat(closeCalled).isTrue();
        assertThat(slotReleasedAfterClose).isTrue();
    }

    @Test
    void concurrentExplicitCloseWaitsForTheInFlightDelegateClose() throws Exception {
        var delegateCloseEntered = new CountDownLatch(1);
        var allowDelegateCloseToFinish = new CountDownLatch(1);
        var delegateCloseFinished = new AtomicBoolean();
        var releasedBeforeCloseFinished = new AtomicBoolean();
        // release() runs only from the SlotReleasingIterator, so sampling the delegate state here
        // pins every release to a finished delegate close even when an explicit close() races the
        // exhaustion close (review round 4).
        var slots = new Semaphore(1) {
            @Override
            public void release() {
                if (!delegateCloseFinished.get()) {
                    releasedBeforeCloseFinished.set(true);
                }
                super.release();
            }
        };
        var model = new LimitedChatModel(slots, new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        delegateCloseEntered.countDown();
                        awaitQuietly(allowDelegateCloseToFinish);
                        delegateCloseFinished.set(true);
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        var exhausted = new AtomicBoolean();
        var exhaustThread = new Thread(() -> exhausted.set(!stream.hasNext()));
        exhaustThread.start();
        assertThat(delegateCloseEntered.await(5, TimeUnit.SECONDS)).isTrue();

        var closeCallStarted = new CountDownLatch(1);
        var closeReturned = new CompletableFuture<Void>();
        var closeThread = new Thread(() -> {
            closeCallStarted.countDown();
            stream.close();
            closeReturned.complete(null);
        });
        closeThread.start();
        try {
            assertThat(closeCallStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // The explicit close must park until the in-flight delegate close finishes; the old
            // release-first behaviour returned here immediately.
            Thread.sleep(200L);
            assertThat(closeReturned).isNotDone();
            assertThat(releasedBeforeCloseFinished).isFalse();
        } finally {
            allowDelegateCloseToFinish.countDown();
            exhaustThread.join(5_000L);
            closeThread.join(5_000L);
        }
        assertThat(exhausted).isTrue();
        assertThat(closeReturned).isDone();
        assertThat(releasedBeforeCloseFinished).isFalse();
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    void reentrantCloseFromTheDelegateCloseDoesNotReleaseTheSlotEarly() {
        var wrapperRef = new AtomicReference<CloseableIterator<String>>();
        var reentrantCloseReturned = new AtomicBoolean();
        var delegateCloseFinished = new AtomicBoolean();
        var releasedBeforeCloseFinished = new AtomicBoolean();
        var closeCount = new AtomicInteger();
        // release() runs only from the SlotReleasingIterator, so sampling the delegate state here
        // pins every release to a finished delegate close even when the delegate re-enters the
        // wrapper's close() from inside its own close (review round 5).
        var slots = new Semaphore(1) {
            @Override
            public void release() {
                if (!delegateCloseFinished.get()) {
                    releasedBeforeCloseFinished.set(true);
                }
                super.release();
            }
        };
        var model = new LimitedChatModel(slots, new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        closeCount.incrementAndGet();
                        wrapperRef.get().close();
                        reentrantCloseReturned.set(true);
                        delegateCloseFinished.set(true);
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        wrapperRef.set(stream);
        assertThat(stream.hasNext()).isFalse();

        assertThat(reentrantCloseReturned).isTrue();
        assertThat(closeCount).hasValue(1);
        assertThat(releasedBeforeCloseFinished).isFalse();
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    void reentrantHasNextFromTheDelegateCloseDoesNotReleaseTheSlotEarly() {
        var wrapperRef = new AtomicReference<CloseableIterator<String>>();
        var reentrantHasNextResult = new AtomicReference<Boolean>();
        var delegateCloseFinished = new AtomicBoolean();
        var releasedBeforeCloseFinished = new AtomicBoolean();
        var slots = new Semaphore(1) {
            @Override
            public void release() {
                if (!delegateCloseFinished.get()) {
                    releasedBeforeCloseFinished.set(true);
                }
                super.release();
            }
        };
        var model = new LimitedChatModel(slots, new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        reentrantHasNextResult.set(wrapperRef.get().hasNext());
                        delegateCloseFinished.set(true);
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        wrapperRef.set(stream);
        stream.close();

        assertThat(reentrantHasNextResult).hasValue(false);
        assertThat(releasedBeforeCloseFinished).isFalse();
        assertThat(slots.availablePermits()).isEqualTo(1);
    }

    @Test
    void explicitClosePropagatesTheDelegateErrorAndStillReleasesTheSlot() {
        var slots = new Semaphore(1);
        var closed = new AtomicInteger();
        var model = new LimitedChatModel(slots, new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public String next() {
                        return "chunk";
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        throw new AssertionError("provider close failed");
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThatThrownBy(stream::close)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("provider close failed");

        assertThat(closed).hasValue(1);
        assertThat(slots.availablePermits()).isEqualTo(1);

        stream.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void delegateHasNextErrorsStillCloseTheDelegateAndReleaseTheSlot() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var closed = new AtomicInteger();
        var broken = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new AssertionError("delegate blew up");
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                        throw new AssertionError("provider close failed");
                    }
                };
            }
        });

        var stream = broken.stream(request("stream"));
        assertThatThrownBy(stream::hasNext)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("delegate blew up");
        assertThat(closed).hasValue(1);

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");

        stream.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void delegateNextErrorsStillCloseTheDelegateAndReleaseTheSlot() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var closed = new AtomicInteger();
        var broken = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return true;
                    }

                    @Override
                    public String next() {
                        throw new AssertionError("chunk blew up");
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
            }
        });

        var stream = broken.stream(request("stream"));
        assertThatThrownBy(stream::next)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("chunk blew up");
        assertThat(closed).hasValue(1);

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");

        stream.close();
        assertThat(closed).hasValue(1);
    }

    @Test
    void delegateCloseErrorsAfterExhaustionDoNotMaskCompletionAndStillReleaseTheSlot() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        throw new AssertionError("close blew up");
                    }
                };
            }
        });

        var stream = model.stream(request("stream"));
        assertThat(stream.hasNext()).isFalse();

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");

        stream.close();
    }

    @Test
    void streamCreationErrorsReleaseTheSlotImmediately() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var failing = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                throw new AssertionError("provider down");
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                throw new AssertionError("provider down");
            }
        });

        assertThatThrownBy(() -> failing.stream(request("stream")))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("provider down");

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");
    }

    @Test
    void failingCloseAfterExhaustionDoesNotTurnTheCompletedReadIntoAnError() {
        var budget = new LlmConcurrencyBudget(1, 8);
        var model = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        return false;
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }

                    @Override
                    public void close() {
                        throw new IllegalStateException("provider close failed");
                    }
                };
            }
        });

        try (var stream = model.stream(request("stream"))) {
            assertThat(stream.hasNext()).isFalse();
        }
    }

    @Test
    void streamCreationFailuresReleaseTheSlotImmediately() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var failing = budget.limitChat("query", (ChatModel) request -> {
            throw new IllegalStateException("provider down");
        });

        assertThatThrownBy(() -> failing.stream(request("stream")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("provider down");

        var recovered = CompletableFuture.supplyAsync(
            () -> budget.limitChat("query", (ChatModel) request -> "answered").generate(request("answer")));
        assertThat(recovered.get(5, TimeUnit.SECONDS)).isEqualTo("answered");
    }

    @Test
    void failingStreamsReleaseTheirSlotExactlyOnce() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var broken = budget.limitChat("query", new ChatModel() {
            @Override
            public String generate(ChatRequest request) {
                return "unused";
            }

            @Override
            public CloseableIterator<String> stream(ChatRequest request) {
                return new CloseableIterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new IllegalStateException("stream broken");
                    }

                    @Override
                    public String next() {
                        throw new NoSuchElementException();
                    }
                };
            }
        });

        try (var stream = broken.stream(request("stream"))) {
            assertThatThrownBy(() -> stream.hasNext())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream broken");
        }

        // The single permit must be back exactly once: holding it parks a second caller, so a
        // double release (which would leave two permits) would let the second caller through.
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitChat("query", (ChatModel) request -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            return "held";
        });
        var holderThread = new Thread(() -> holder.generate(request("hold")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var second = new Thread(() -> budget.limitChat("query", (ChatModel) request -> "second")
                .generate(request("second")));
            second.start();
            awaitParked(second);
            releaseHolder.countDown();
            holderThread.join(5_000L);
            second.join(5_000L);
            assertThat(second.isAlive()).isFalse();
        } finally {
            releaseHolder.countDown();
            holderThread.join();
        }
    }

    @Test
    void interruptedWaitersFailFastAndRestoreTheInterruptFlag() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitChat("query", (ChatModel) request -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            return "held";
        });
        var waiter = budget.limitChat("query", (ChatModel) request -> "waited");

        var holderThread = new Thread(() -> holder.generate(request("hold")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var failure = new AtomicReference<RuntimeException>();
        var interruptFlagRestored = new AtomicInteger();
        var waiterThread = new Thread(() -> {
            try {
                waiter.generate(request("wait"));
            } catch (RuntimeException exception) {
                failure.set(exception);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted() ? 1 : 0);
            }
        });
        waiterThread.start();
        awaitParked(waiterThread);
        waiterThread.interrupt();
        waiterThread.join(5_000L);
        try {
            assertThat(failure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("interrupted while waiting for an LLM slot");
            assertThat(interruptFlagRestored).hasValue(1);
        } finally {
            releaseHolder.countDown();
            holderThread.join();
        }
    }

    @Test
    void doesNotFailUncontendedCallsWhenTheInterruptFlagIsAlreadySet() {
        var budget = new LlmConcurrencyBudget(1, 1);
        var chat = budget.limitChat("extract", (ChatModel) request -> "answered");
        var embedding = budget.limitEmbedding(texts -> texts.stream().map(text -> List.of(1.0d)).toList());

        Thread.currentThread().interrupt();
        try {
            assertThat(chat.generate(request("prompt"))).isEqualTo("answered");
            assertThat(embedding.embedAll(List.of("text"))).hasSize(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsNonPositiveBudgets() {
        assertThatThrownBy(() -> new LlmConcurrencyBudget(0, 8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxAsyncLlm must be positive");
        assertThatThrownBy(() -> new LlmConcurrencyBudget(4, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("embeddingMaxAsync must be positive");
    }

    private static ChatModel.ChatRequest request(String prompt) {
        return new ChatModel.ChatRequest("System prompt", prompt);
    }

    private static List<List<Double>> vectors(List<String> texts) {
        return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for latch release", exception);
        }
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static final class ConcurrencyRecordingChatModel implements ChatModel {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String generate(ChatRequest request) {
            calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20L);
                return "ok";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            } finally {
                active.decrementAndGet();
            }
        }

        private int peakConcurrency() {
            return peak.get();
        }

        private int calls() {
            return calls.get();
        }
    }
}
