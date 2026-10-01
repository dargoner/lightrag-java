package io.github.lightrag.model;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Bounds model concurrency with one fair semaphore per LLM role plus one for embeddings,
 * mirroring the upstream per-role limiters ({@code DEFAULT_MAX_ASYNC = 4},
 * {@code DEFAULT_EMBEDDING_FUNC_MAX_ASYNC = 8}, {@code DEFAULT_MAX_PARALLEL_INSERT = 3};
 * {@code constants.py:95,96,715} and {@code llm_roles.py:186-199}). Upstream's priority
 * ordering (summary runs at priority 8, {@code constants.py:683}) is intentionally not
 * ported because Java's pipelines block instead of yielding; fair FIFO queueing is the
 * substitute.
 *
 * <p>A slot lives exactly as long as one delegate call: this class deliberately exposes no
 * standalone acquire/release API, so SDK code can never hold a slot across a
 * platform-level gate wait (gate first, model second). Slots are not re-entrant — do not
 * call a budget-limited model of a role from inside another model call of that role.
 *
 * <p>{@code generate} and {@code generateResponse} are bounded; {@code stream} is not,
 * because a streamed response is consumed after the call window closes.
 *
 * <p>A call that finds a free slot takes it without consulting the interrupt status, so a
 * pending cancellation flag never fails an uncontended call. Only a thread that actually has
 * to queue aborts on interruption (flag restored, {@code IllegalStateException}); the budget
 * orders calls, it does not decide cancellation.
 */
public final class LlmConcurrencyBudget {
    public static final int DEFAULT_MAX_ASYNC_LLM = 4;
    public static final int DEFAULT_EMBEDDING_MAX_ASYNC = 8;

    private final int maxAsyncLlm;
    private final int embeddingMaxAsync;
    private final Map<String, Semaphore> roleSemaphores = new ConcurrentHashMap<>();
    private final Semaphore embeddingSemaphore;

    public LlmConcurrencyBudget(int maxAsyncLlm, int embeddingMaxAsync) {
        if (maxAsyncLlm <= 0) {
            throw new IllegalArgumentException("maxAsyncLlm must be positive");
        }
        if (embeddingMaxAsync <= 0) {
            throw new IllegalArgumentException("embeddingMaxAsync must be positive");
        }
        this.maxAsyncLlm = maxAsyncLlm;
        this.embeddingMaxAsync = embeddingMaxAsync;
        this.embeddingSemaphore = new Semaphore(embeddingMaxAsync, true);
    }

    public ChatModel limitChat(String role, ChatModel delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new LimitedChatModel(semaphoreFor(role), delegate);
    }

    public EmbeddingModel limitEmbedding(EmbeddingModel delegate) {
        return new LimitedEmbeddingModel(embeddingSemaphore, Objects.requireNonNull(delegate, "delegate"));
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
