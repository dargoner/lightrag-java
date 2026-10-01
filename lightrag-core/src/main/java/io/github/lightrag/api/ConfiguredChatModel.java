package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.model.ChatResponse;
import io.github.lightrag.model.CloseableIterator;

import java.util.Objects;

/**
 * Wraps injected role models so builder-level options act as merge defaults (request fields win).
 */
final class ConfiguredChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ChatRequestOptions defaults;

    ConfiguredChatModel(ChatModel delegate, ChatRequestOptions defaults) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
    }

    @Override
    public String generate(ChatRequest request) {
        return delegate.generate(withDefaults(request));
    }

    @Override
    public ChatResponse generateResponse(ChatRequest request) {
        return delegate.generateResponse(withDefaults(request));
    }

    @Override
    public String cacheIdentity() {
        // The answer cache keys the request before this wrapper merges its defaults in, so the
        // defaults must ride in the identity or differently configured models share cache entries.
        return delegate.cacheIdentity() + defaults.cacheIdentitySuffix();
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        return delegate.stream(withDefaults(request));
    }

    private ChatRequest withDefaults(ChatRequest request) {
        var merged = defaults.merge(request.options());
        if (merged.equals(request.options())) {
            return request;
        }
        return new ChatRequest(
            request.systemPrompt(),
            request.userPrompt(),
            request.conversationHistory(),
            merged
        );
    }
}
