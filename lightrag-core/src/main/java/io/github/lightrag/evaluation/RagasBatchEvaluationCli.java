package io.github.lightrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.api.QueryMode;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.openai.OpenAiCompatibleChatModel;
import io.github.lightrag.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightrag.model.openai.OpenAiCompatibleRerankModel;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
        var rerankConfig = createRerankSettings(System.getenv());
        var service = new RagasBatchEvaluationService();
        var results = service.evaluateBatch(
            batchRequest,
            createChatModel(batchRequest),
            new OpenAiCompatibleEmbeddingModel(
                envOrFallback("LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL", "LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
                envOrDefault("LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"),
                requiredEnv("LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY", "LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY")
            ),
            rerankConfig == null ? null : rerankConfig.settings()
        );
        printEnvelope(new FileOutputStream(FileDescriptor.out), new OutputEnvelope(
            new RequestMetadata(
                batchRequest.documentsDir(),
                batchRequest.datasetPath(),
                batchRequest.mode(),
                batchRequest.topK(),
                batchRequest.chunkTopK(),
                batchRequest.maxHop(),
                batchRequest.pathTopK(),
                batchRequest.multiHopEnabled(),
                batchRequest.storageProfile(),
                batchRequest.retrievalOnly(),
                config.runLabel(),
                rerankConfig == null ? null : rerankConfig.modelName(),
                rerankConfig == null ? 0 : rerankConfig.settings().candidateMultiplier()
            ),
            new Summary(results.size()),
            results
        ));
    }

    /**
     * Writes the envelope as UTF-8 bytes regardless of the platform default charset.
     * Relying on {@code System.out} here corrupts non-ASCII answers and contexts on
     * hosts whose console encoding is not UTF-8 (the stdout contract with the RAGAS
     * harness is UTF-8 JSON).
     */
    static void printEnvelope(OutputStream target, OutputEnvelope envelope) throws IOException {
        var writer = new PrintWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8));
        writer.println(OBJECT_MAPPER.writeValueAsString(envelope));
        writer.flush();
    }

    static BatchCliConfig buildConfig(Map<String, String> arguments) {
        return new BatchCliConfig(
            new RagasBatchEvaluationService.BatchRequest(
                Path.of(arguments.getOrDefault("--documents-dir", DEFAULT_DOCUMENTS_DIR)),
                Path.of(arguments.getOrDefault("--dataset", DEFAULT_DATASET_PATH)),
                QueryMode.valueOf(arguments.getOrDefault("--mode", QueryMode.MIX.name()).toUpperCase(java.util.Locale.ROOT)),
                Integer.parseInt(arguments.getOrDefault("--top-k", "10")),
                Integer.parseInt(arguments.getOrDefault("--chunk-top-k", "10")),
                Integer.parseInt(arguments.getOrDefault("--max-hop", "2")),
                Integer.parseInt(arguments.getOrDefault("--path-top-k", "3")),
                Boolean.parseBoolean(arguments.getOrDefault("--multi-hop-enabled", "true")),
                RagasStorageProfile.fromValue(arguments.getOrDefault("--storage-profile", "in-memory")),
                Boolean.parseBoolean(arguments.getOrDefault("--retrieval-only", "false"))
            ),
            arguments.getOrDefault("--run-label", DEFAULT_RUN_LABEL)
        );
    }

    record BatchCliConfig(RagasBatchEvaluationService.BatchRequest batchRequest, String runLabel) {
    }

    record RerankCliConfig(String modelName, RagasBatchEvaluationService.RerankSettings settings) {
    }

    record OutputEnvelope(RequestMetadata request, Summary summary, java.util.List<RagasBatchEvaluationService.Result> results) {
    }

    record RequestMetadata(
        Path documentsDir,
        Path datasetPath,
        QueryMode mode,
        int topK,
        int chunkTopK,
        int maxHop,
        int pathTopK,
        boolean multiHopEnabled,
        RagasStorageProfile storageProfile,
        boolean retrievalOnly,
        String runLabel,
        String rerankModel,
        int rerankCandidateMultiplier
    ) {
    }

    record Summary(int totalCases) {
    }

    /**
     * Builds the optional rerank settings from the environment; a missing
     * {@code LIGHTRAG_JAVA_EVAL_RERANK_MODEL} leaves rerank disabled.
     */
    static RerankCliConfig createRerankSettings(Map<String, String> environment) {
        var modelName = blankToNull(environment.get("LIGHTRAG_JAVA_EVAL_RERANK_MODEL"));
        if (modelName == null) {
            return null;
        }
        var baseUrl = firstNonBlank(
            environment.get("LIGHTRAG_JAVA_EVAL_RERANK_BASE_URL"),
            environment.get("LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL"),
            environment.get("LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL"),
            "https://api.openai.com/v1/"
        );
        var apiKey = firstNonBlank(
            environment.get("LIGHTRAG_JAVA_EVAL_RERANK_API_KEY"),
            environment.get("LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY"),
            environment.get("LIGHTRAG_JAVA_EVAL_CHAT_API_KEY"),
            environment.get("OPENAI_API_KEY")
        );
        if (apiKey == null) {
            throw new IllegalStateException(
                "Missing required environment variable. Checked: LIGHTRAG_JAVA_EVAL_RERANK_API_KEY, "
                    + "LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY, LIGHTRAG_JAVA_EVAL_CHAT_API_KEY, OPENAI_API_KEY"
            );
        }
        var timeoutSeconds = Long.parseLong(environment.getOrDefault("LIGHTRAG_JAVA_EVAL_RERANK_TIMEOUT_SECONDS", "60"));
        var candidateMultiplier = Integer.parseInt(environment.getOrDefault("LIGHTRAG_JAVA_EVAL_RERANK_CANDIDATE_MULTIPLIER", "3"));
        var minScore = Double.parseDouble(environment.getOrDefault("LIGHTRAG_JAVA_EVAL_RERANK_MIN_SCORE", "0"));
        var model = new OpenAiCompatibleRerankModel(baseUrl, modelName, apiKey, Duration.ofSeconds(timeoutSeconds));
        return new RerankCliConfig(
            modelName,
            new RagasBatchEvaluationService.RerankSettings(model, candidateMultiplier, minScore)
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            var candidate = blankToNull(value);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
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
