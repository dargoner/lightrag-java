package io.github.lightragjava.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.api.QueryMode;
import io.github.lightragjava.model.openai.OpenAiCompatibleChatModel;
import io.github.lightragjava.model.openai.OpenAiCompatibleEmbeddingModel;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public final class RagasEvaluationCli {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        var arguments = parseArgs(args);
        var documentsDir = Path.of(requireArg(arguments, "--documents-dir"));
        var question = requireArg(arguments, "--question");
        var mode = QueryMode.valueOf(arguments.getOrDefault("--mode", QueryMode.MIX.name()).toUpperCase(java.util.Locale.ROOT));
        var topK = Integer.parseInt(arguments.getOrDefault("--top-k", "10"));
        var chunkTopK = Integer.parseInt(arguments.getOrDefault("--chunk-top-k", "10"));

        var service = new RagasEvaluationService();
        var result = service.evaluate(
            new RagasEvaluationService.Request(documentsDir, question, mode, topK, chunkTopK),
            new OpenAiCompatibleChatModel(
                envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
                envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_MODEL", "gpt-4o-mini"),
                requiredEnv("LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY"),
                Duration.ofSeconds(Long.parseLong(envOrDefault("LIGHTRAG_JAVA_EVAL_CHAT_TIMEOUT_SECONDS", "120")))
            ),
            new OpenAiCompatibleEmbeddingModel(
                envOrFallback("LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL", "LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
                envOrDefault("LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"),
                requiredEnv("LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY", "LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY")
            )
        );

        System.out.println(OBJECT_MAPPER.writeValueAsString(new OutputEnvelope(
            new RequestMetadata(documentsDir, question, mode, topK, chunkTopK),
            result
        )));
    }

    record OutputEnvelope(RequestMetadata request, RagasEvaluationService.EvaluationResult result) {
    }

    record RequestMetadata(Path documentsDir, String question, QueryMode mode, int topK, int chunkTopK) {
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
