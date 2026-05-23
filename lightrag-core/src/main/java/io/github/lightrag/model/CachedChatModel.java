package io.github.lightrag.model;

import io.github.lightrag.storage.LlmCacheStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class CachedChatModel implements ChatModel {
    private final String role;
    private final ChatModel delegate;
    private final LlmCacheStore cacheStore;

    public CachedChatModel(String role, ChatModel delegate, LlmCacheStore cacheStore) {
        this.role = requireNonBlank(role, "role").toLowerCase(java.util.Locale.ROOT);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cacheStore = Objects.requireNonNull(cacheStore, "cacheStore");
    }

    @Override
    public String generate(ChatRequest request) {
        var cacheId = cacheId(role, request);
        var cached = cacheStore.load(cacheId);
        if (cached.isPresent()) {
            return cached.get().value();
        }
        var response = delegate.generate(request);
        cacheStore.save(new LlmCacheStore.CacheRecord(cacheId, response));
        return response;
    }

    public static String cacheId(String role, ChatRequest request) {
        Objects.requireNonNull(request, "request");
        var canonical = new StringBuilder()
            .append("role=").append(requireNonBlank(role, "role")).append('\n')
            .append("system=").append(request.systemPrompt()).append('\n')
            .append("user=").append(request.userPrompt()).append('\n');
        for (var message : request.conversationHistory()) {
            canonical
                .append("history.role=").append(message.role()).append('\n')
                .append("history.content=").append(message.content()).append('\n');
        }
        return "default:" + role.toLowerCase(java.util.Locale.ROOT) + ":" + sha256(canonical.toString());
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
