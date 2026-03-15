package io.github.lightragjava.model;

import java.util.List;
import java.util.Objects;

public interface ChatModel {
    String generate(ChatRequest request);

    record ChatRequest(
        String systemPrompt,
        String userPrompt,
        List<ConversationMessage> conversationHistory
    ) {
        public ChatRequest {
            systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
            conversationHistory = List.copyOf(Objects.requireNonNull(conversationHistory, "conversationHistory"));
        }

        public ChatRequest(String systemPrompt, String userPrompt) {
            this(systemPrompt, userPrompt, List.of());
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
