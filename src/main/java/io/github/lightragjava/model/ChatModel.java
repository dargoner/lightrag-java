package io.github.lightragjava.model;

import java.util.Objects;

public interface ChatModel {
    String generate(ChatRequest request);

    record ChatRequest(String systemPrompt, String userPrompt) {
        public ChatRequest {
            systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
            userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        }
    }
}
