package io.github.lightragjava.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.exception.ModelException;
import io.github.lightragjava.model.EmbeddingModel;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient = new OkHttpClient();
    private final String baseUrl;
    private final String modelName;
    private final String apiKey;

    public OpenAiCompatibleEmbeddingModel(String baseUrl, String modelName, String apiKey) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = requireNonBlank(modelName, "modelName");
        this.apiKey = requireNonBlank(apiKey, "apiKey");
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
                    throw new ModelException("Embedding request failed with status " + response.code());
                }
                var body = response.body();
                if (body == null) {
                    throw new ModelException("Embedding response body is missing");
                }
                return extractEmbeddings(OBJECT_MAPPER.readTree(body.byteStream()));
            }
        } catch (IOException exception) {
            throw new ModelException("Embedding request failed", exception);
        }
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
