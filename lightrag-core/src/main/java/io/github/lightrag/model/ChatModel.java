package io.github.lightrag.model;

import java.util.List;
import java.util.Objects;

public interface ChatModel {
    String generate(ChatRequest request);

    /** Providers that expose response metadata override this; the default adapts the plain-content contract. */
    default ChatResponse generateResponse(ChatRequest request) {
        return ChatResponse.of(generate(request));
    }

    default CloseableIterator<String> stream(ChatRequest request) {
        var response = generate(request);
        if (response.isEmpty()) {
            return CloseableIterator.empty();
        }
        return CloseableIterator.of(List.of(response));
    }

    record ChatRequest(
        String systemPrompt,
        String userPrompt,
        List<ConversationMessage> conversationHistory,
        ChatRequestOptions options
    ) {
        public ChatRequest {
            systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
            conversationHistory = List.copyOf(Objects.requireNonNull(conversationHistory, "conversationHistory"));
            options = options == null ? ChatRequestOptions.NONE : options;
        }

        public ChatRequest(String systemPrompt, String userPrompt) {
            this(systemPrompt, userPrompt, List.of(), ChatRequestOptions.NONE);
        }

        public ChatRequest(String systemPrompt, String userPrompt, List<ConversationMessage> conversationHistory) {
            this(systemPrompt, userPrompt, conversationHistory, ChatRequestOptions.NONE);
        }

        public ChatRequest(String systemPrompt, String userPrompt, ChatRequestOptions options) {
            this(systemPrompt, userPrompt, List.of(), options);
        }

        public record ConversationMessage(String role, String content) {
            public ConversationMessage {
                role = requireNonBlank(role, "role");
                content = Objects.requireNonNull(content, "content");
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
}
