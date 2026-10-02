package io.github.lightrag.model;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Bounds model concurrency with one fair semaphore per LLM role plus one for embeddings,
 * mirroring the upstream per-role limiters ({@code DEFAULT_MAX_ASYNC = 4},
 * {@code DEFAULT_EMBEDDING_FUNC_MAX_ASYNC = 8}, {@code DEFAULT_MAX_PARALLEL_INSERT = 3};
 * {@code constants.py:95,96,715} and {@code llm_roles.py:186-199}). Chat-role calls keep strict
 * per-role FIFO: upstream's summary-priority (8) ahead of processing (10,
 * {@code constants.py:671-673}) has no observable effect in Java because summary and extraction
 * use independent pools, so fair queueing is the closer substitute there. The shared embedding
 * pool keeps the upstream two-level ordering: {@link EmbeddingPriority#HIGH} query embeddings
 * (upstream priority 5, {@code operate.py:5347-5349}) jump ahead of queued
 * {@link EmbeddingPriority#LOW} ingestion embeddings (upstream processing priority 10).
 *
 * <p>A slot lives exactly as long as one delegate call: this class deliberately exposes no
 * standalone acquire/release API, so SDK code can never hold a slot across a
 * platform-level gate wait (gate first, model second). Slots are not re-entrant — do not
 * call a budget-limited model of a role from inside another model call of that role.
 *
 * <p>{@code generate}, {@code generateResponse} and {@code stream} are all bounded: a streamed
 * response holds its slot until the returned iterator is exhausted, closed or fails, so open
 * provider connections cannot exceed the role budget.
 *
 * <p>A call that finds a free slot takes it without consulting the interrupt status, so a
 * pending cancellation flag never fails an uncontended call. Only a thread that actually has
 * to queue aborts on interruption (flag restored, {@code IllegalStateException}); the budget
 * orders calls, it does not decide cancellation.
 */
public final class LlmConcurrencyBudget {
    public static final int DEFAULT_MAX_ASYNC_LLM = 4;
    public static final int DEFAULT_EMBEDDING_MAX_ASYNC = 8;

    /**
     * Orders waiters inside the shared embedding pool. Constant order matters: {@link #HIGH} must
     * be declared before {@link #LOW}, because the queue key is the enum ordinal.
     */
    public enum EmbeddingPriority {
        HIGH,
        LOW
    }

    private final int maxAsyncLlm;
    private final int embeddingMaxAsync;
    private final Map<String, Semaphore> roleSemaphores = new ConcurrentHashMap<>();
    private final PrioritySemaphore embeddingSemaphore;

    public LlmConcurrencyBudget(int maxAsyncLlm, int embeddingMaxAsync) {
        if (maxAsyncLlm <= 0) {
            throw new IllegalArgumentException("maxAsyncLlm must be positive");
        }
        if (embeddingMaxAsync <= 0) {
            throw new IllegalArgumentException("embeddingMaxAsync must be positive");
        }
        this.maxAsyncLlm = maxAsyncLlm;
        this.embeddingMaxAsync = embeddingMaxAsync;
        this.embeddingSemaphore = new PrioritySemaphore(embeddingMaxAsync);
    }

    public ChatModel limitChat(String role, ChatModel delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new LimitedChatModel(semaphoreFor(role), delegate);
    }

    /**
     * Wraps an embedding model so each call takes one slot of the shared embedding pool at the
     * given priority.
     */
    public EmbeddingModel limitEmbedding(EmbeddingPriority priority, EmbeddingModel delegate) {
        Objects.requireNonNull(priority, "priority");
        return new LimitedEmbeddingModel(
            embeddingSemaphore,
            priority,
            Objects.requireNonNull(delegate, "delegate")
        );
    }

    /** Delegates to {@link #limitEmbedding(EmbeddingPriority, EmbeddingModel)} with {@link EmbeddingPriority#LOW}. */
    public EmbeddingModel limitEmbedding(EmbeddingModel delegate) {
        return limitEmbedding(EmbeddingPriority.LOW, delegate);
    }

    public int maxAsyncLlm() {
        return maxAsyncLlm;
    }

    public int embeddingMaxAsync() {
        return embeddingMaxAsync;
    }

    private Semaphore semaphoreFor(String role) {
        Objects.requireNonNull(role, "role");
        var normalized = role.strip().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        return roleSemaphores.computeIfAbsent(normalized, ignored -> new Semaphore(maxAsyncLlm, true));
    }
}
