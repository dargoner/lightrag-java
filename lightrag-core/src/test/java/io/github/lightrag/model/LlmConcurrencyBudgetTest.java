package io.github.lightrag.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    void streamedResponsesDoNotConsumeSlots() throws Exception {
        var budget = new LlmConcurrencyBudget(1, 8);
        var holderEntered = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = budget.limitChat("query", (ChatModel) request -> {
            holderEntered.countDown();
            awaitQuietly(releaseHolder);
            return "held";
        });
        var streamer = budget.limitChat("query", (ChatModel) request -> "streamed");

        var holderThread = new Thread(() -> holder.generate(request("hold")));
        holderThread.start();
        assertThat(holderEntered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            var streamed = CompletableFuture.supplyAsync(() -> {
                try (var iterator = streamer.stream(request("stream"))) {
                    var chunks = new ArrayList<String>();
                    iterator.forEachRemaining(chunks::add);
                    return chunks;
                }
            });
            assertThat(streamed.get(5, TimeUnit.SECONDS)).containsExactly("streamed");
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
