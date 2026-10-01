package io.github.lightrag.model;

import java.util.Objects;

/** Chat completion content plus provider metadata: the finish reason and token usage. */
public record ChatResponse(String content, String finishReason, Usage usage) {
    public ChatResponse {
        content = Objects.requireNonNull(content, "content");
    }

    public record Usage(Integer promptTokens, Integer completionTokens) {
    }

    /** True when the provider cut the answer off at the output token limit ({@code finish_reason=length}). */
    public boolean truncated() {
        return "length".equalsIgnoreCase(finishReason == null ? "" : finishReason);
    }

    public static ChatResponse of(String content) {
        return new ChatResponse(content, null, null);
    }
}
