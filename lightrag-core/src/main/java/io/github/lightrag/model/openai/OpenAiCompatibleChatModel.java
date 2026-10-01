package io.github.lightrag.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatRequestOptions;
import io.github.lightrag.model.ChatResponse;
import io.github.lightrag.model.CloseableIterator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class OpenAiCompatibleChatModel implements ChatModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatModel.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final ChatRequestOptions defaults;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public OpenAiCompatibleChatModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, Duration.ofSeconds(30), ChatRequestOptions.NONE);
    }

    public OpenAiCompatibleChatModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        this(baseUrl, modelName, apiKey, timeout, ChatRequestOptions.NONE);
    }

    public OpenAiCompatibleChatModel(
        String baseUrl,
        String modelName,
        String apiKey,
        Duration timeout,
        ChatRequestOptions defaults
    ) {
        this(
            baseUrl,
            modelName,
            apiKey,
            timeout,
            defaults,
            ModelRetrySupport.DEFAULT_MAX_ATTEMPTS,
            ModelRetrySupport.DEFAULT_INITIAL_BACKOFF
        );
    }

    public OpenAiCompatibleChatModel(
        String baseUrl,
        String modelName,
        String apiKey,
        Duration timeout,
        ChatRequestOptions defaults,
        int maxAttempts,
        Duration initialBackoff
    ) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        var effectiveTimeout = Objects.requireNonNull(timeout, "timeout");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.maxAttempts = requireValidMaxAttempts(maxAttempts);
        this.initialBackoff = requireNonNegative(initialBackoff, "initialBackoff");
        this.httpClient = new OkHttpClient.Builder()
            .callTimeout(effectiveTimeout)
            .connectTimeout(effectiveTimeout)
            .readTimeout(effectiveTimeout)
            .writeTimeout(effectiveTimeout)
            .build();
    }

    @Override
    public String cacheIdentity() {
        return "openai-compatible:" + modelName + "@" + baseUrl;
    }

    @Override
    public String generate(ChatRequest request) {
        return generateResponse(request).content();
    }

    @Override
    public ChatResponse generateResponse(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return ModelRetrySupport.call(() -> generateOnce(request), maxAttempts, initialBackoff);
    }

    private ChatResponse generateOnce(ChatRequest request) {
        try (var response = execute(buildHttpRequest(request, false))) {
            var body = response.body();
            if (body == null) {
                throw new ModelException("Chat completion response body is missing");
            }
            return toChatResponse(OBJECT_MAPPER.readTree(body.byteStream()));
        } catch (IOException exception) {
            throw toModelException("Chat completion request", baseUrl + "chat/completions", exception);
        }
    }

    @Override
    public CloseableIterator<String> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        return ModelRetrySupport.call(() -> openStream(request), maxAttempts, initialBackoff);
    }

    private CloseableIterator<String> openStream(ChatRequest request) {
        try {
            var response = execute(buildHttpRequest(request, true));
            var body = response.body();
            if (body == null) {
                response.close();
                throw new ModelException("Chat completion response body is missing");
            }
            return new OpenAiSseIterator(response, body.source(), response.request().url().toString());
        } catch (IOException exception) {
            throw toModelException("Chat completion request", baseUrl + "chat/completions", exception);
        }
    }

    private static ChatResponse toChatResponse(JsonNode root) {
        var choice = root.path("choices").path(0);
        var content = choice.path("message").path("content");
        if (!content.isTextual()) {
            throw new ModelException("Chat completion response is missing choices[0].message.content");
        }
        var finishReason = choice.path("finish_reason");
        var usage = root.path("usage");
        return new ChatResponse(
            content.asText(),
            finishReason.isTextual() ? finishReason.asText() : null,
            usage.isObject()
                ? new ChatResponse.Usage(
                    intOrNull(usage.path("prompt_tokens")),
                    intOrNull(usage.path("completion_tokens")))
                : null
        );
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
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
        var options = defaults.merge(request.options());
        if (options.temperature() != null) {
            payload.put("temperature", options.temperature());
        }
        if (options.maxTokens() != null) {
            payload.put("max_tokens", options.maxTokens());
        }
        if (options.topP() != null) {
            payload.put("top_p", options.topP());
        }
        if (options.responseFormat() != null) {
            payload.put("response_format", Map.of("type", options.responseFormat()));
        }
        if (stream) {
            payload.put("stream", true);
        }
        return payload;
    }

    private Response execute(Request request) throws IOException {
        var response = httpClient.newCall(request).execute();
        if (!response.isSuccessful()) {
            try (response) {
                var body = response.body();
                var responseBody = body == null ? null : body.string();
                throw new ModelException(
                    "Chat completion request failed",
                    response.code(),
                    compactResponseBody(responseBody),
                    request.url().toString(),
                    response.header("x-request-id")
                );
            }
        }
        return response;
    }

    private static ModelException toModelException(String operation, String requestUrl, IOException exception) {
        if (isTimeout(exception)) {
            return new ModelTimeoutException(operation + " timed out", exception, requestUrl);
        }
        return new ModelException(operation + " failed", null, null, requestUrl, null, exception);
    }

    private static boolean isTimeout(IOException exception) {
        if (exception instanceof SocketTimeoutException) {
            return true;
        }
        if (exception instanceof InterruptedIOException interrupted) {
            var message = interrupted.getMessage();
            return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("timeout");
        }
        return false;
    }

    private static String compactResponseBody(String responseBody) {
        if (responseBody == null) {
            return null;
        }
        var normalized = responseBody.strip();
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500) + "...";
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

    private static int requireValidMaxAttempts(int maxAttempts) {
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must not be negative");
        }
        return maxAttempts;
    }

    private static Duration requireNonNegative(Duration value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static final class OpenAiSseIterator implements CloseableIterator<String> {
        private final Response response;
        private final okio.BufferedSource source;
        private final String requestUrl;
        private String nextChunk;
        private boolean closed;
        private boolean completed;

        private OpenAiSseIterator(Response response, okio.BufferedSource source, String requestUrl) {
            this.response = response;
            this.source = source;
            this.requestUrl = requestUrl;
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
                    var data = readNextEventData();
                    if (data == null) {
                        close();
                        return;
                    }
                    if (data.equals("[DONE]")) {
                        close();
                        return;
                    }
                    var root = OBJECT_MAPPER.readTree(data);
                    var finishReason = extractFinishReason(root);
                    if (finishReason != null && finishReason.equalsIgnoreCase("length")) {
                        log.warn("Chat completion stream ended at the output token limit"
                            + " (finish_reason=length), returning partial content");
                    }
                    var chunk = extractDeltaContent(root);
                    if (chunk != null && !chunk.isEmpty()) {
                        nextChunk = chunk;
                        return;
                    }
                }
            } catch (IOException exception) {
                close();
                throw toModelException("Chat completion stream", requestUrl, exception);
            }
        }

        private String readNextEventData() throws IOException {
            StringBuilder data = null;
            while (!closed && !completed) {
                var line = source.readUtf8Line();
                if (line == null) {
                    return data == null ? null : data.toString();
                }
                if (line.isBlank()) {
                    if (data != null) {
                        return data.toString();
                    }
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                if (data == null) {
                    data = new StringBuilder();
                } else {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).stripLeading());
            }
            return data == null ? null : data.toString();
        }

        private static String extractDeltaContent(JsonNode root) {
            var content = root.path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : null;
        }

        private static String extractFinishReason(JsonNode root) {
            var finishReason = root.path("choices").path(0).path("finish_reason");
            return finishReason.isTextual() ? finishReason.asText() : null;
        }
    }
}
