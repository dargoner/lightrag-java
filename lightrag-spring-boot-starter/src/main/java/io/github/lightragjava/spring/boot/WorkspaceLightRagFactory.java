package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.LightRag;
import io.github.lightragjava.indexing.Chunker;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.neo4j.Neo4jGraphConfig;
import io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class WorkspaceLightRagFactory {
    private static final int WORKSPACE_HASH_LENGTH = 8;

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final ObjectProvider<StorageProvider> storageProvider;
    private final Chunker chunker;
    private final RerankModel rerankModel;
    private final SnapshotStore snapshotStore;
    private final LightRagProperties properties;
    private final ConcurrentMap<String, LightRag> cache = new ConcurrentHashMap<>();

    public WorkspaceLightRagFactory(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        ObjectProvider<StorageProvider> storageProvider,
        ObjectProvider<Chunker> chunker,
        ObjectProvider<RerankModel> rerankModel,
        SnapshotStore snapshotStore,
        LightRagProperties properties
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.chunker = chunker.getIfAvailable();
        this.rerankModel = rerankModel.getIfAvailable();
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public LightRag get(String workspaceId) {
        var normalizedWorkspaceId = normalizeWorkspaceId(workspaceId);
        var cached = cache.get(normalizedWorkspaceId);
        if (cached != null) {
            return cached;
        }
        synchronized (cache) {
            cached = cache.get(normalizedWorkspaceId);
            if (cached != null) {
                return cached;
            }
            if (cache.size() >= maxActiveWorkspaces()) {
                throw new IllegalStateException("workspace cache limit exceeded");
            }
            var created = create(normalizedWorkspaceId);
            cache.put(normalizedWorkspaceId, created);
            return created;
        }
    }

    public Optional<LightRag> find(String workspaceId) {
        return Optional.ofNullable(cache.get(normalizeWorkspaceId(workspaceId)));
    }

    private LightRag create(String workspaceId) {
        var query = properties.getQuery();
        var builder = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .storage(createStorageProvider(workspaceId))
            .automaticQueryKeywordExtraction(query.isAutomaticKeywordExtraction())
            .rerankCandidateMultiplier(query.getRerankCandidateMultiplier());
        if (properties.getIndexing().getEmbeddingBatchSize() > 0) {
            builder.embeddingBatchSize(properties.getIndexing().getEmbeddingBatchSize());
        }
        if (chunker != null) {
            builder.chunker(chunker);
        }
        if (rerankModel != null) {
            builder.rerankModel(rerankModel);
        }
        var snapshotPath = resolveSnapshotPath(workspaceId);
        if (snapshotPath != null) {
            builder.loadFromSnapshot(snapshotPath);
        }
        return builder.build();
    }

    private StorageProvider createStorageProvider(String workspaceId) {
        var existingStorageProvider = storageProvider.getIfAvailable();
        if (isDefaultWorkspace(workspaceId) && existingStorageProvider != null) {
            return existingStorageProvider;
        }
        if (!isDefaultWorkspace(workspaceId) && isCustomStorageProvider(existingStorageProvider)) {
            throw new IllegalStateException("non-default workspaces require starter-managed storage providers");
        }
        return switch (properties.getStorage().getType()) {
            case IN_MEMORY -> InMemoryStorageProvider.create(snapshotStore);
            case POSTGRES -> new PostgresStorageProvider(postgresConfig(workspaceId), snapshotStore);
            case POSTGRES_NEO4J -> {
                if (!isDefaultWorkspace(workspaceId)) {
                    throw new IllegalStateException("workspace isolation does not support storage type POSTGRES_NEO4J");
                }
                yield new PostgresNeo4jStorageProvider(postgresConfig(workspaceId), neo4jConfig(), snapshotStore);
            }
        };
    }

    private PostgresStorageConfig postgresConfig(String workspaceId) {
        var postgres = properties.getStorage().getPostgres();
        if (postgres.getVectorDimensions() == null) {
            throw new IllegalStateException("lightrag.storage.postgres.vector-dimensions is required");
        }
        return new PostgresStorageConfig(
            requireValue(postgres.getJdbcUrl(), "lightrag.storage.postgres.jdbc-url"),
            requireValue(postgres.getUsername(), "lightrag.storage.postgres.username"),
            requireValue(postgres.getPassword(), "lightrag.storage.postgres.password"),
            requireValue(postgres.getSchema(), "lightrag.storage.postgres.schema"),
            postgres.getVectorDimensions(),
            resolveTablePrefix(workspaceId)
        );
    }

    private Neo4jGraphConfig neo4jConfig() {
        var neo4j = properties.getStorage().getNeo4j();
        return new Neo4jGraphConfig(
            requireValue(neo4j.getUri(), "lightrag.storage.neo4j.uri"),
            requireValue(neo4j.getUsername(), "lightrag.storage.neo4j.username"),
            requireValue(neo4j.getPassword(), "lightrag.storage.neo4j.password"),
            requireValue(neo4j.getDatabase(), "lightrag.storage.neo4j.database")
        );
    }

    private String resolveTablePrefix(String workspaceId) {
        var basePrefix = requireValue(
            properties.getStorage().getPostgres().getTablePrefix(),
            "lightrag.storage.postgres.table-prefix"
        );
        if (isDefaultWorkspace(workspaceId)) {
            return basePrefix;
        }
        return basePrefix + "ws_" + slug(workspaceId) + "_" + shortHash(workspaceId) + "_";
    }

    private Path resolveSnapshotPath(String workspaceId) {
        var configuredSnapshotPath = properties.getSnapshotPath();
        if (configuredSnapshotPath == null || configuredSnapshotPath.isBlank()) {
            return null;
        }
        var basePath = Path.of(configuredSnapshotPath.strip());
        if (isDefaultWorkspace(workspaceId)) {
            return basePath;
        }
        var fileName = basePath.getFileName().toString();
        var extensionIndex = fileName.lastIndexOf('.');
        var baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        var extension = extensionIndex >= 0 ? fileName.substring(extensionIndex) : "";
        var derivedName = baseName + "-" + slug(workspaceId) + "-" + shortHash(workspaceId) + extension;
        return basePath.resolveSibling(derivedName);
    }

    private boolean isDefaultWorkspace(String workspaceId) {
        return normalizeWorkspaceId(workspaceId).equals(normalizeWorkspaceId(properties.getWorkspace().getDefaultId()));
    }

    private String normalizeWorkspaceId(String workspaceId) {
        var candidate = workspaceId;
        if (candidate == null || candidate.isBlank()) {
            candidate = properties.getWorkspace().getDefaultId();
        }
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalStateException("lightrag.workspace.default-id must not be blank");
        }
        return candidate.strip();
    }

    private int maxActiveWorkspaces() {
        var value = properties.getWorkspace().getMaxActiveWorkspaces();
        if (value <= 0) {
            throw new IllegalStateException("lightrag.workspace.max-active-workspaces must be positive");
        }
        return value;
    }

    private static boolean isCustomStorageProvider(StorageProvider storageProvider) {
        if (storageProvider == null) {
            return false;
        }
        return !(storageProvider instanceof InMemoryStorageProvider)
            && !(storageProvider instanceof PostgresStorageProvider)
            && !(storageProvider instanceof PostgresNeo4jStorageProvider);
    }

    private static String slug(String workspaceId) {
        var normalized = Objects.requireNonNull(workspaceId, "workspaceId")
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return normalized.isEmpty() ? "workspace" : normalized;
    }

    private static String shortHash(String workspaceId) {
        var hex = Integer.toUnsignedString(Objects.requireNonNull(workspaceId, "workspaceId").hashCode(), 16);
        if (hex.length() >= WORKSPACE_HASH_LENGTH) {
            return hex.substring(0, WORKSPACE_HASH_LENGTH);
        }
        return "0".repeat(WORKSPACE_HASH_LENGTH - hex.length()) + hex;
    }

    private static String requireValue(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }
}
