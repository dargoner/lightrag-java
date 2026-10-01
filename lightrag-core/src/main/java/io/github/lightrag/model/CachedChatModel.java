package io.github.lightrag.model;

import io.github.lightrag.storage.LlmCacheStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CachedChatModel implements ChatModel {
    private static final String CACHE_POLICY_VERSION = "v2";
    private static final String ANSWER_ROLE = "query";

    private final String role;
    private final ChatModel delegate;
    private final String identity;
    private final LlmCacheStore cacheStore;

    public CachedChatModel(String role, ChatModel delegate, LlmCacheStore cacheStore) {
        this.role = requireNonBlank(role, "role").toLowerCase(java.util.Locale.ROOT);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.identity = requireNonBlank(delegate.cacheIdentity(), "delegate.cacheIdentity()");
        this.cacheStore = Objects.requireNonNull(cacheStore, "cacheStore");
    }

    @Override
    public String generate(ChatRequest request) {
        return generateResponse(request).content();
    }

    @Override
    public ChatResponse generateResponse(ChatRequest request) {
        if (ANSWER_ROLE.equals(role) && !request.conversationHistory().isEmpty()) {
            // Upstream bypasses only the answer cache for history-carrying requests
            // (operate.py:4683-4690); the extraction cache folds the history into its
            // key instead (utils.py:5645-5662), keeping recorded cache ids reachable.
            return delegate.generateResponse(request);
        }
        var cacheId = cacheId(role, identity, request);
        var cached = cacheStore.load(cacheId);
        if (cached.isPresent()) {
            return ChatResponse.of(cached.get().value());
        }
        var response = delegate.generateResponse(request);
        if (!response.truncated()) {
            cacheStore.save(new LlmCacheStore.CacheRecord(cacheId, response.content()));
        }
        return response;
    }

    @Override
    public String cacheIdentity() {
        return identity;
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        return delegate.stream(request);
    }

    public static String cacheId(String role, String identity, ChatRequest request) {
        Objects.requireNonNull(request, "request");
        var canonical = new StringBuilder()
            .append("role=").append(requireNonBlank(role, "role")).append('\n')
            .append("system=").append(request.systemPrompt()).append('\n')
            .append("user=").append(request.userPrompt()).append('\n')
            .append("options.temperature=").append(ChatRequestOptions.encodeForCacheKey(request.options().temperature())).append('\n')
            .append("options.maxTokens=").append(ChatRequestOptions.encodeForCacheKey(request.options().maxTokens())).append('\n')
            .append("options.topP=").append(ChatRequestOptions.encodeForCacheKey(request.options().topP())).append('\n')
            .append("options.responseFormat=").append(ChatRequestOptions.encodeForCacheKey(request.options().responseFormat())).append('\n');
        for (var message : request.conversationHistory()) {
            canonical
                .append("history.role=").append(message.role()).append('\n')
                .append("history.content=").append(message.content()).append('\n');
        }
        // The identity is hashed so the key stays bounded no matter how long the base URL or the
        // defaults suffix grows: llm_cache.cache_id is VARCHAR(191) on MySQL, and an oversized key
        // fails the INSERT instead of merely missing the cache.
        return CACHE_POLICY_VERSION + ":" + role.toLowerCase(java.util.Locale.ROOT) + ":"
            + sha256(requireNonBlank(identity, "identity")) + ":" + sha256(canonical.toString());
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
