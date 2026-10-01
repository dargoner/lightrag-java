package io.github.lightrag.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.exception.ModelException;
import io.github.lightrag.exception.ModelTimeoutException;
import io.github.lightrag.model.RerankModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP rerank binding speaking the Cohere/Jina compatible {@code POST {baseUrl}rerank} shape:
 * {@code {model, query, documents: [<candidate text>], top_n: <candidate count>}}, parsing
 * {@code results: [{index, relevance_score}]} back into candidate ids. Provider-specific
 * extensions (for example the Aliyun nested {@code input}/{@code parameters} payload or
 * {@code rerank_model_max_tokens}) are not covered.
 *
 * <p>Results are validated strictly: a non-integer or out-of-range index, or a missing or
 * non-numeric score, fails the call with {@link ModelException} instead of being dropped
 * silently.
 */
public final class OpenAiCompatibleRerankModel implements RerankModel {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public OpenAiCompatibleRerankModel(String baseUrl, String modelName, String apiKey) {
        this(baseUrl, modelName, apiKey, Duration.ofSeconds(30));
    }

    public OpenAiCompatibleRerankModel(String baseUrl, String modelName, String apiKey, Duration timeout) {
        this(
            baseUrl,
            modelName,
            apiKey,
            timeout,
            ModelRetrySupport.DEFAULT_MAX_ATTEMPTS,
            ModelRetrySupport.DEFAULT_INITIAL_BACKOFF
        );
    }

    public OpenAiCompatibleRerankModel(
        String baseUrl,
        String modelName,
        String apiKey,
        Duration timeout,
        int maxAttempts,
        Duration initialBackoff
    ) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
        var effectiveTimeout = Objects.requireNonNull(timeout, "timeout");
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
    public List<RerankResult> rerank(RerankRequest request) {
        var candidates = Objects.requireNonNull(request, "request").candidates();
        if (candidates.isEmpty()) {
            return List.of();
        }
        return ModelRetrySupport.call(() -> rerankOnce(request), maxAttempts, initialBackoff);
    }

    private List<RerankResult> rerankOnce(RerankRequest request) {
        try {
            var httpRequest = new Request.Builder()
                .url(baseUrl + "rerank")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(OBJECT_MAPPER.writeValueAsBytes(buildPayload(request)), JSON))
                .build();
            try (var response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    var body = response.body();
                    var responseBody = body == null ? null : body.string();
                    throw new ModelException(
                        "Rerank request failed",
                        response.code(),
                        compactResponseBody(responseBody),
                        httpRequest.url().toString(),
                        response.header("x-request-id")
                    );
                }
                var body = response.body();
                if (body == null) {
                    throw new ModelException("Rerank response body is missing");
                }
                return toRerankResults(request.candidates(), OBJECT_MAPPER.readTree(body.byteStream()));
            }
        } catch (IOException exception) {
            throw toModelException("Rerank request", baseUrl + "rerank", exception);
        }
    }

    private Map<String, Object> buildPayload(RerankRequest request) {
        var documents = request.candidates().stream()
            .map(RerankModel.RerankCandidate::text)
            .toList();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("model", modelName);
        payload.put("query", request.query());
        payload.put("documents", documents);
        payload.put("top_n", documents.size());
        return payload;
    }

    private static List<RerankResult> toRerankResults(List<RerankCandidate> candidates, JsonNode root) {
        var results = root.path("results");
        if (!results.isArray()) {
            throw new ModelException("Rerank response is missing results array");
        }
        var reranked = new ArrayList<RerankResult>();
        for (var item : results) {
            var index = item.path("index");
            if (!index.isIntegralNumber()) {
                throw new ModelException("Rerank result item is missing an integer index");
            }
            var candidateIndex = index.asInt();
            if (candidateIndex < 0 || candidateIndex >= candidates.size()) {
                throw new ModelException("Rerank result index " + candidateIndex + " is out of range");
            }
            var score = item.path("relevance_score");
            if (!score.isNumber() || !Double.isFinite(score.doubleValue())) {
                throw new ModelException("Rerank result score must be a finite number");
            }
            reranked.add(new RerankResult(candidates.get(candidateIndex).id(), score.doubleValue()));
        }
        return List.copyOf(reranked);
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
}
