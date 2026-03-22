package io.github.lightragjava.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleChatModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class RagasBatchEvaluationCli {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_DOCUMENTS_DIR = "evaluation/ragas/sample_documents";
    private static final String DEFAULT_DATASET_PATH = "evaluation/ragas/sample_dataset.json";
    private static final String DEFAULT_RUN_LABEL = "baseline";
    private static final String EMPTY_GRAPH_EXTRACTION_RESPONSE = """
        {
          "entities": [],
          "relations": []
        }
        """;

    public static void main(String[] args) throws Exception {
        var config = buildConfig(parseArgs(args));
        var batchRequest = config.batchRequest();
        var service = new RagasBatchEvaluationService();
        var results = service.evaluateBatch(
            batchRequest,
            createChatModel(batchRequest),
            new OpenAiCompatibleEmbeddingModel(
                envOrFallback("LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL", "LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
                envOrDefault("LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"),
                requiredEnv("LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY", "LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY")
            )
        );
        System.out.println(OBJECT_MAPPER.writeValueAsString(new OutputEnvelope(
            new RequestMetadata(
                batchRequest.documentsDir(),
                batchRequest.datasetPath(),
                batchRequest.mode(),
                batchRequest.topK(),
                batchRequest.chunkTopK(),
                batchRequest.storageProfile(),
                batchRequest.retrievalOnly(),
                config.runLabel()
            ),
            new Summary(results.size()),
            results
        )));
    }

    static BatchCliConfig buildConfig(Map<String, String> arguments) {
        return new BatchCliConfig(
            new RagasBatchEvaluationService.BatchRequest(
                Path.of(arguments.getOrDefault("--documents-dir", DEFAULT_DOCUMENTS_DIR)),
                Path.of(arguments.getOrDefault("--dataset", DEFAULT_DATASET_PATH)),
                QueryMode.valueOf(arguments.getOrDefault("--mode", QueryMode.MIX.name()).toUpperCase(java.util.Locale.ROOT)),
                Integer.parseInt(arguments.getOrDefault("--top-k", "10")),
                Integer.parseInt(arguments.getOrDefault("--chunk-top-k", "10")),
                RagasStorageProfile.fromValue(arguments.getOrDefault("--storage-profile", "in-memory")),
                Boolean.parseBoolean(arguments.getOrDefault("--retrieval-only", "false"))
            ),
            arguments.getOrDefault("--run-label", DEFAULT_RUN_LABEL)
        );
    }

    record BatchCliConfig(RagasBatchEvaluationService.BatchRequest batchRequest, String runLabel) {
    }

    record OutputEnvelope(RequestMetadata request, Summary summary, java.util.List<RagasBatchEvaluationService.Result> results) {
    }

    record RequestMetadata(
        Path documentsDir,
        Path datasetPath,
        QueryMode mode,
        int topK,
        int chunkTopK,
        RagasStorageProfile storageProfile,
        boolean retrievalOnly,
        String runLabel
    ) {
    }

    record Summary(int totalCases) {
    }

    static ChatModel createChatModel(RagasBatchEvaluationService.BatchRequest batchRequest) {
        if (batchRequest.retrievalOnly()) {
            return request -> EMPTY_GRAPH_EXTRACTION_RESPONSE;
        }
        return new OpenAiCompatibleChatModel(
            envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
            envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_MODEL", "gpt-4o-mini"),
            requiredEnv("LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY"),
            Duration.ofSeconds(Long.parseLong(envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_TIMEOUT_SECONDS", "120")))
        );
    }

    private static Map<String, String> parseArgs(String[] args) {
        var parsed = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + arg);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for " + arg);
            }
            parsed.put(arg, args[++i]);
        }
        return parsed;
    }

    private static String requireArg(Map<String, String> args, String key) {
        var value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument " + key);
        }
        return value;
    }

    private static String envOrDefault(String key, String defaultValue) {
        var value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String envOrFallback(String key, String fallbackKey, String defaultValue) {
        var value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return envOrDefault(fallbackKey, defaultValue);
    }

    private static String requiredEnv(String... keys) {
        for (var key : keys) {
            var value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Missing required environment variable. Checked: " + String.join(", ", keys));
    }
}
