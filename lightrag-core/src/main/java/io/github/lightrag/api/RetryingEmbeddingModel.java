package io.github.lightrag.api;

import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.openai.ModelRetrySupport;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Retries an injected embedding model's calls with the shared model retry policy. */
final class RetryingEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final int maxAttempts;
    private final Duration initialBackoff;

    RetryingEmbeddingModel(EmbeddingModel delegate, int maxAttempts, Duration initialBackoff) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxAttempts = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff, "initialBackoff");
    }

    @Override
    public String cacheIdentity() {
        return delegate.cacheIdentity();
    }

    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        return ModelRetrySupport.call(() -> delegate.embedAll(texts), maxAttempts, initialBackoff);
    }
}
