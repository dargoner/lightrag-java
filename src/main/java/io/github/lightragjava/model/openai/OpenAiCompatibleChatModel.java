package io.github.lightragjava.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ModelException;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.CloseableIterator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
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
        try (var response = execute(buildHttpRequest(request, false))) {
            var body = response.body();
            if (body == null) {
                throw new ModelException("Chat completion response body is missing");
            }
            return extractContent(OBJECT_MAPPER.readTree(body.byteStream()));
        } catch (IOException exception) {
            throw new ModelException("Chat completion request failed", exception);
        }
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            var response = execute(buildHttpRequest(request, true));
            var body = response.body();
            if (body == null) {
                response.close();
                throw new ModelException("Chat completion response body is missing");
            }
            return new OpenAiSseIterator(response, body.source());
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

    private Request buildHttpRequest(ChatRequest request, boolean stream) throws IOException {
        return new Request.Builder()
            .url(baseUrl + "chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(buildPayload(request, stream)), JSON))
            .build();
    }

    private Map<String, Object> buildPayload(ChatRequest request, boolean stream) {
        var messages = new java.util.ArrayList<Map<String, String>>();
        if (!request.systemPrompt().isBlank()) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (var message : request.conversationHistory()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("messages", List.copyOf(messages));
        if (stream) {
            payload.put("stream", true);
        }
        return payload;
    }

    private Response execute(Request request) throws IOException {
        var response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            try (response) {
                throw new ModelException("Chat completion request failed with status " + response.code());
            }
        }
        return response;
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

    private static final class OpenAiSseIterator implements CloseableIterator<String> {
        private final Response response;
        private final okio.BufferedSource source;
        private String nextChunk;
        private boolean closed;
        private boolean completed;

        private OpenAiSseIterator(Response response, okio.BufferedSource source) {
            this.response = response;
            this.source = source;
        }

        @Override
        public boolean hasNext() {
            if (closed || completed) {
                return false;
            }
            if (nextChunk != null) {
                return true;
            }
            loadNext();
            return nextChunk != null;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            var chunk = nextChunk;
            nextChunk = null;
            return chunk;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            completed = true;
            nextChunk = null;
            response.close();
        }

        private void loadNext() {
            try {
                while (!closed && !completed) {
                    var line = source.readUtf8Line();
                    if (line == null) {
                        close();
                        return;
                    }
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    var data = line.substring("data:".length()).trim();
                    if (data.equals("[DONE]")) {
                        close();
                        return;
                    }
                    var chunk = extractDeltaContent(OBJECT_MAPPER.readTree(data));
                    if (chunk != null && !chunk.isEmpty()) {
                        nextChunk = chunk;
                        return;
                    }
                }
            } catch (IOException exception) {
                close();
                throw new ModelException("Chat completion stream failed", exception);
            }
        }

        private static String extractDeltaContent(JsonNode root) {
            var content = root.path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : null;
        }
    }
}
