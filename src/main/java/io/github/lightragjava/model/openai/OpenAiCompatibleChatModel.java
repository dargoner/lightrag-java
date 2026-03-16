package io.github.lightragjava.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ModelException;
import io.github.lightragjava.model.ChatModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenAiCompatibleChatModel implements ChatModel {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;

    public OpenAiCompatibleChatModel(String baseUrl, String modelName, String apiKey) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
    }

    @Override
    public String generate(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        var messages = new java.util.ArrayList<Map<String, String>>();
        if (!request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (var message : request.conversationHistory()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        var payload = Map.of(
            "model", modelName,
            "messages", List.copyOf(messages)
        );

        try {
            var httpRequest = new Request.Builder()
                .url(baseUrl + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(payload), JSON))
                .build();
            try (var response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new ModelException("Chat completion request failed with status " + response.code());
                }
                var body = response.body();
                if (body == null) {
                    throw new ModelException("Chat completion response body is missing");
                }
                return extractContent(OBJECT_MAPPER.readTree(body.byteStream()));
            }
        } catch (IOException exception) {
            throw new ModelException("Chat completion request failed", exception);
        }
    }

    private static String extractContent(JsonNode root) {
        var content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new ModelException("Chat completion response is missing choices[0].message.content");
        }
        return content.asText();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        var normalized = requireNonBlank(baseUrl, "baseUrl");
        return normalized.endsWith("/") ? normalized : normalized + "/";
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
