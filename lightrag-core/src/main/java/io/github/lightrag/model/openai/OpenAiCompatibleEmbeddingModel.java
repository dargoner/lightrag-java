package io.github.lightrag.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import io.github.lightrag.model.EmbeddingModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, Duration.ofSeconds(30));
    }

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        var effectiveTimeout = Objects.requireNonNull(timeout, "timeout");
        this.httpClient = new OkHttpClient.Builder()
            .callTimeout(effectiveTimeout)
            .connectTimeout(effectiveTimeout)
            .readTimeout(effectiveTimeout)
            .writeTimeout(effectiveTimeout)
            .build();
    }

    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        var inputs = List.copyOf(Objects.requireNonNull(texts, "texts"));
        if (inputs.isEmpty()) {
            return List.of();
        }

        var payload = Map.of(
            "model", modelName,
            "input", inputs
        );

        try {
            var httpRequest = new Request.Builder()
                .url(baseUrl + "embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(payload), JSON))
                .build();
            try (var response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    var body = response.body();
                    var responseBody = body == null ? null : body.string();
                    throw new ModelException(
                        "Embedding request failed",
                        response.code(),
                        compactResponseBody(responseBody),
                        httpRequest.url().toString(),
                        response.header("x-request-id")
                    );
                }
                var body = response.body();
                if (body == null) {
                    throw new ModelException("Embedding response body is missing");
                }
                return extractEmbeddings(OBJECT_MAPPER.readTree(body.byteStream()));
            }
        } catch (IOException exception) {
            throw toModelException("Embedding request", baseUrl + "embeddings", exception);
        }
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

    private static List<List<Double>> extractEmbeddings(JsonNode root) {
        var data = root.path("data");
        if (!data.isArray()) {
            throw new ModelException("Embedding response is missing data array");
        }

        var embeddings = new ArrayList<List<Double>>();
        for (var item : data) {
            var embedding = item.path("embedding");
            if (!embedding.isArray()) {
                throw new ModelException("Embedding response item is missing embedding array");
            }
            var vector = new ArrayList<Double>();
            for (var value : embedding) {
                if (!value.isNumber()) {
                    throw new ModelException("Embedding values must be numeric");
                }
                vector.add(value.doubleValue());
            }
            embeddings.add(List.copyOf(vector));
        }
        return List.copyOf(embeddings);
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
