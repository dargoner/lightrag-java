package io.github.lightrag.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.indexing.StorageSnapshots;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.model.LlmConcurrencyBudget;
import io.github.lightrag.model.openai.OpenAiCompatibleEmbeddingModel;
import io.github.lightrag.persistence.FileSnapshotStore;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.neo4j.Neo4jGraphConfig;
import io.github.lightrag.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightrag.storage.postgres.PostgresStorageConfig;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Offline {@code rebuild-vdb} entry point: re-embeds the vector namespaces of one workspace from their
 * authoritative stores (chunk store, graph store) after they drifted from the configured embedding model.
 *
 * <p>Stop all writers on the workspace before running a rebuild (ingestion, deletion, graph edits):
 * the three vector namespaces are replaced as a whole, so a concurrent write would be lost.
 *
 * <pre>
 * ./gradlew :lightrag-core:runRebuildVdb --args="--mode check  --workspace default --storage-profile in-memory --snapshot-file build/drift-store.json"
 * ./gradlew :lightrag-core:runRebuildVdb --args="--mode rebuild --force --workspace default --storage-profile in-memory --snapshot-file build/drift-store.json"
 * </pre>
 *
 * The embedding model is configured through the RAGAS evaluation environment variables
 * ({@code LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL/_MODEL/_API_KEY}, falling back to the chat variables and
 * {@code OPENAI_API_KEY}).
 */
public final class RebuildVectorIndexCommand {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    private RebuildVectorIndexCommand() {
    }

    public static void main(String[] args) throws Exception {
        var arguments = parseArgs(args);
        var mode = RebuildMode.fromValue(arguments.getOrDefault("--mode", "check"));
        var workspaceId = arguments.getOrDefault("--workspace", "default");
        var snapshotFile = optionalPath(arguments.get("--snapshot-file"));
        var force = arguments.containsKey("--force");
        var profile = arguments.getOrDefault("--storage-profile", "in-memory");

        try (var storage = openStorage(profile, snapshotFile)) {
            var service = new RebuildVectorIndexService(
                storage.provider(),
                createEmbeddingModel(),
                new LlmConcurrencyBudget(LlmConcurrencyBudget.DEFAULT_MAX_ASYNC_LLM, LlmConcurrencyBudget.DEFAULT_EMBEDDING_MAX_ASYNC),
                force
            );
            var report = mode == RebuildMode.CHECK ? service.check(workspaceId) : service.rebuild(workspaceId);
            if (mode == RebuildMode.REBUILD && storage.snapshotPath() != null) {
                storage.provider().snapshotStore().save(
                    storage.snapshotPath(),
                    StorageSnapshots.capture(storage.provider())
                );
            }
            System.out.println(OBJECT_MAPPER.writeValueAsString(toOutput(workspaceId, mode, profile, force, snapshotFile, report)));
        }
    }

    private static Map<String, Object> toOutput(
        String workspaceId,
        RebuildMode mode,
        String profile,
        boolean force,
        Path snapshotFile,
        RebuildVectorIndexService.RebuildReport report
    ) {
        var output = new LinkedHashMap<String, Object>();
        output.put("workspace", workspaceId);
        output.put("mode", mode.name().toLowerCase(Locale.ROOT));
        output.put("storageProfile", profile);
        output.put("force", force);
        if (snapshotFile != null) {
            output.put("snapshotFile", snapshotFile.toAbsolutePath().normalize().toString());
        }
        output.put("clean", report.clean());
        output.put("missingItems", report.missingItems());
        output.put("staleItems", report.staleItems());
        output.put("embeddedItems", report.embeddedItems());
        output.put("missingChunkVectorIds", report.missingChunkVectorIds());
        output.put("staleChunkVectorIds", report.staleChunkVectorIds());
        output.put("missingEntityVectorIds", report.missingEntityVectorIds());
        output.put("staleEntityVectorIds", report.staleEntityVectorIds());
        output.put("missingRelationVectorIds", report.missingRelationVectorIds());
        output.put("staleRelationVectorIds", report.staleRelationVectorIds());
        return output;
    }

    private static StorageHandle openStorage(String profile, Path snapshotFile) {
        var normalizedProfile = profile.trim().toLowerCase(Locale.ROOT);
        return switch (normalizedProfile) {
            case "in-memory", "in_memory" -> {
                if (snapshotFile == null) {
                    yield new StorageHandle(InMemoryStorageProvider.create(), null, () -> {
                    });
                }
                var fileSnapshotStore = new FileSnapshotStore();
                var provider = InMemoryStorageProvider.create(fileSnapshotStore);
                provider.restore(fileSnapshotStore.load(snapshotFile));
                yield new StorageHandle(provider, snapshotFile, () -> {
                });
            }
            case "postgres-neo4j-testcontainers", "postgres_neo4j_testcontainers" -> {
                if (snapshotFile != null) {
                    throw new IllegalArgumentException("--snapshot-file is only supported by the in-memory profile");
                }
                yield postgresNeo4jHandle(createEmbeddingModel());
            }
            default -> throw new IllegalArgumentException("Unsupported storage profile: " + profile);
        };
    }

    private static StorageHandle postgresNeo4jHandle(EmbeddingModel embeddingModel) {
        var postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
        );
        var neo4j = new Neo4jContainer<>("neo4j:5-community").withAdminPassword("password");
        postgres.start();
        neo4j.start();
        try {
            int vectorDimensions = embeddingModel.embedAll(List.of("dimension probe")).get(0).size();
            var provider = new PostgresNeo4jStorageProvider(
                new PostgresStorageConfig(
                    postgres.getJdbcUrl(),
                    postgres.getUsername(),
                    postgres.getPassword(),
                    "lightrag",
                    vectorDimensions,
                    "rag_"
                ),
                new Neo4jGraphConfig(neo4j.getBoltUrl(), "neo4j", neo4j.getAdminPassword(), "neo4j"),
                new FileSnapshotStore()
            );
            return new StorageHandle(provider, null, () -> {
                closeQuietly(provider);
                closeQuietly(neo4j);
                closeQuietly(postgres);
            });
        } catch (RuntimeException exception) {
            closeQuietly(neo4j);
            closeQuietly(postgres);
            throw exception;
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort cleanup of an offline tool
        }
    }

    static EmbeddingModel createEmbeddingModel() {
        return new OpenAiCompatibleEmbeddingModel(
            envOrFallback("LIGHTRAG_JAVA_EVAL_EMBEDDING_BASE_URL", "LIGHTRAG_JAVA_EVAL_CHAT_BASE_URL", "https://api.openai.com/v1/"),
            envOrDefault("LIGHTRAG_JAVA_EVAL_EMBEDDING_MODEL", "text-embedding-3-small"),
            requiredEnv("LIGHTRAG_JAVA_EVAL_EMBEDDING_API_KEY", "LIGHTRAG_JAVA_EVAL_CHAT_API_KEY", "OPENAI_API_KEY"),
            Duration.ofSeconds(Long.parseLong(
                envOrDefault("LIGHTRAG_JAVA_EVAL_EMBEDDING_TIMEOUT_SECONDS", Integer.toString(DEFAULT_TIMEOUT_SECONDS))
            ))
        );
    }

    static Map<String, String> parseArgs(String[] args) {
        var parsed = new HashMap<String, String>();
        var arguments = Objects.requireNonNull(args, "args");
        for (int index = 0; index < arguments.length; index++) {
            var arg = arguments[index];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected positional argument: " + arg);
            }
            if ("--force".equals(arg)) {
                parsed.put(arg, "true");
                continue;
            }
            var separator = arg.indexOf('=');
            if (separator > 0) {
                parsed.put(arg.substring(0, separator), arg.substring(separator + 1));
                continue;
            }
            if (index + 1 >= arguments.length) {
                throw new IllegalArgumentException("Missing value for " + arg + " (use --option value or --option=value)");
            }
            parsed.put(arg, arguments[++index]);
        }
        return parsed;
    }

    private static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
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

    private enum RebuildMode {
        CHECK,
        REBUILD;

        static RebuildMode fromValue(String value) {
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "check" -> CHECK;
                case "rebuild" -> REBUILD;
                default -> throw new IllegalArgumentException("Unsupported mode: " + value + " (expected check or rebuild)");
            };
        }
    }

    private record StorageHandle(AtomicStorageProvider provider, Path snapshotPath, Runnable cleanup) implements AutoCloseable {
        @Override
        public void close() {
            cleanup.run();
        }
    }
}
