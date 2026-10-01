package io.github.lightrag.model;

/**
 * Binding options sent alongside the chat payload: sampling parameters and the response format
 * (upstream {@code llm/binding_options.py}, the {@code temperature} / {@code max_tokens} / {@code top_p} /
 * {@code response_format} keys).
 *
 * <p>A {@code null} field means "not set" and is omitted from the provider payload. Model-level defaults
 * merge with per-request options via {@link #merge(ChatRequestOptions)}, where the request's non-null
 * fields win.</p>
 */
public record ChatRequestOptions(Double temperature, Integer maxTokens, Double topP, String responseFormat) {
    /** No options at all: every field unset. */
    public static final ChatRequestOptions NONE = new ChatRequestOptions(null, null, null, null);

    /** Asks the provider for a JSON object response ({@code response_format={"type":"json_object"}}). */
    public static final ChatRequestOptions JSON_OBJECT = new ChatRequestOptions(null, null, null, "json_object");

    public ChatRequestOptions {
        if (temperature != null && (temperature < 0d || temperature > 2d)) {
            throw new IllegalArgumentException("temperature must be between 0 and 2");
        }
        if (maxTokens != null && maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (topP != null && (topP < 0d || topP > 1d)) {
            throw new IllegalArgumentException("topP must be between 0 and 1");
        }
        if (responseFormat != null && responseFormat.isBlank()) {
            throw new IllegalArgumentException("responseFormat must not be blank");
        }
    }

    /**
     * Cache-identity suffix describing these options, for {@link ChatModel#cacheIdentity()} implementors
     * that apply them as merge defaults. Empty for {@link #NONE} (so wrappers without defaults keep the
     * delegate identity untouched); otherwise every field is encoded so models configured with
     * different defaults cannot share answer-cache entries.
     */
    public String cacheIdentitySuffix() {
        if (equals(NONE)) {
            return "";
        }
        return "|defaults:t=" + encodeForCacheKey(temperature)
            + ",max_tokens=" + encodeForCacheKey(maxTokens)
            + ",top_p=" + encodeForCacheKey(topP)
            + ",format=" + encodeForCacheKey(responseFormat);
    }

    /**
     * Presence-tagged encoding for cache keys: an unset field and a literal {@code "null"} string
     * would both stringify to {@code null} and collide, although one field is omitted from the
     * provider payload and the other is sent.
     */
    static String encodeForCacheKey(Object value) {
        return value == null ? "n" : "v:" + value;
    }

    /**
     * Merge the given override into these options: every non-null field of {@code override} wins, unset
     * fields fall back to this instance. The receiver is the base, so a per-request override beats the
     * model-level default ({@code defaults.merge(request.options())}).
     */
    public ChatRequestOptions merge(ChatRequestOptions override) {
        if (override == null) {
            return this;
        }
        return new ChatRequestOptions(
            override.temperature() != null ? override.temperature() : temperature,
            override.maxTokens() != null ? override.maxTokens() : maxTokens,
            override.topP() != null ? override.topP() : topP,
            override.responseFormat() != null ? override.responseFormat() : responseFormat
        );
    }
}
