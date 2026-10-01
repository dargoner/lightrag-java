package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatResponse;
import io.github.lightrag.model.CloseableIterator;
import io.github.lightrag.model.openai.ModelRetrySupport;

import java.time.Duration;
import java.util.Objects;

/** Retries an injected model's calls with the shared model retry policy. */
final class RetryingChatModel implements ChatModel {
    private final ChatModel delegate;
    private final int maxAttempts;
    private final Duration initialBackoff;

    RetryingChatModel(ChatModel delegate, int maxAttempts, Duration initialBackoff) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
    }

    @Override
    public String generate(ChatRequest request) {
        return ModelRetrySupport.call(() -> delegate.generate(request), maxAttempts, initialBackoff);
    }

    @Override
    public ChatResponse generateResponse(ChatRequest request) {
        return ModelRetrySupport.call(() -> delegate.generateResponse(request), maxAttempts, initialBackoff);
    }

    @Override
    public String cacheIdentity() {
        return delegate.cacheIdentity();
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        return ModelRetrySupport.call(() -> delegate.stream(request), maxAttempts, initialBackoff);
    }
}
