package io.github.lightragjava.spring.boot;

import io.github.lightragjava.indexing.FixedWindowChunker;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "lightrag")
public class LightRagProperties {
    private final ModelProperties chat = new ModelProperties();
    private final ModelProperties embedding = new ModelProperties();
    private final StorageProperties storage = new StorageProperties();
    private final IndexingProperties indexing = new IndexingProperties();
    private final QueryProperties query = new QueryProperties();
    private final DemoProperties demo = new DemoProperties();
    private final WorkspaceProperties workspace = new WorkspaceProperties();
    private String snapshotPath;

    public ModelProperties getChat() {
        return chat;
    }

    public ModelProperties getEmbedding() {
        return embedding;
    }

    public StorageProperties getStorage() {
        return storage;
    }

    public IndexingProperties getIndexing() {
        return indexing;
    }

    public QueryProperties getQuery() {
        return query;
    }

    public DemoProperties getDemo() {
        return demo;
    }

    public WorkspaceProperties getWorkspace() {
        return workspace;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public void setSnapshotPath(String snapshotPath) {
        this.snapshotPath = snapshotPath;
    }

    public static class ModelProperties {
        private String baseUrl;
        private String model;
        private String apiKey;
        private Duration timeout = Duration.ofSeconds(30);

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    public static class StorageProperties {
        private Type type = Type.IN_MEMORY;
        private final PostgresProperties postgres = new PostgresProperties();
        private final Neo4jProperties neo4j = new Neo4jProperties();

        public Type getType() {
            return type;
        }

        public void setType(Type type) {
            this.type = type;
        }

        public PostgresProperties getPostgres() {
            return postgres;
        }

        public Neo4jProperties getNeo4j() {
            return neo4j;
        }
    }

    public static class IndexingProperties {
        private final ChunkingProperties chunking = new ChunkingProperties();
        private int embeddingBatchSize;
        private int maxParallelInsert = 1;
        private int entityExtractMaxGleaning = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING;
        private int maxExtractInputTokens = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS;
        private String language = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_LANGUAGE;
        private List<String> entityTypes = io.github.lightragjava.indexing.KnowledgeExtractor.DEFAULT_ENTITY_TYPES;

        public ChunkingProperties getChunking() {
            return chunking;
        }

        public int getEmbeddingBatchSize() {
            return embeddingBatchSize;
        }

        public void setEmbeddingBatchSize(int embeddingBatchSize) {
            this.embeddingBatchSize = embeddingBatchSize;
        }

        public int getMaxParallelInsert() {
            return maxParallelInsert;
        }

        public void setMaxParallelInsert(int maxParallelInsert) {
            if (maxParallelInsert <= 0) {
                throw new IllegalArgumentException("maxParallelInsert must be positive");
            }
            this.maxParallelInsert = maxParallelInsert;
        }

        public int getEntityExtractMaxGleaning() {
            return entityExtractMaxGleaning;
        }

        public void setEntityExtractMaxGleaning(int entityExtractMaxGleaning) {
            if (entityExtractMaxGleaning < 0) {
                throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
            }
            this.entityExtractMaxGleaning = entityExtractMaxGleaning;
        }

        public int getMaxExtractInputTokens() {
            return maxExtractInputTokens;
        }

        public void setMaxExtractInputTokens(int maxExtractInputTokens) {
            if (maxExtractInputTokens <= 0) {
                throw new IllegalArgumentException("maxExtractInputTokens must be positive");
            }
            this.maxExtractInputTokens = maxExtractInputTokens;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = requireNonBlank(language, "language");
        }

        public List<String> getEntityTypes() {
            return entityTypes;
        }

        public void setEntityTypes(List<String> entityTypes) {
            var normalizedEntityTypes = List.copyOf(entityTypes).stream()
                .map(type -> requireNonBlank(type, "entityTypes entry"))
                .toList();
            if (normalizedEntityTypes.isEmpty()) {
                throw new IllegalArgumentException("entityTypes must not be empty");
            }
            this.entityTypes = normalizedEntityTypes;
        }

        private static String requireNonBlank(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return value.strip();
        }
    }

    public static class ChunkingProperties {
        private int windowSize = FixedWindowChunker.DEFAULT_WINDOW_SIZE;
        private int overlap = FixedWindowChunker.DEFAULT_OVERLAP;

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = overlap;
        }
    }

    public static class QueryProperties {
        private String defaultMode = "MIX";
        private int defaultTopK = 10;
        private int defaultChunkTopK = 10;
        private String defaultResponseType = "Multiple Paragraphs";
        private boolean automaticKeywordExtraction = true;
        private int rerankCandidateMultiplier = 2;

        public String getDefaultMode() {
            return defaultMode;
        }

        public void setDefaultMode(String defaultMode) {
            this.defaultMode = defaultMode;
        }

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public int getDefaultChunkTopK() {
            return defaultChunkTopK;
        }

        public void setDefaultChunkTopK(int defaultChunkTopK) {
            this.defaultChunkTopK = defaultChunkTopK;
        }

        public String getDefaultResponseType() {
            return defaultResponseType;
        }

        public void setDefaultResponseType(String defaultResponseType) {
            this.defaultResponseType = defaultResponseType;
        }

        public boolean isAutomaticKeywordExtraction() {
            return automaticKeywordExtraction;
        }

        public void setAutomaticKeywordExtraction(boolean automaticKeywordExtraction) {
            this.automaticKeywordExtraction = automaticKeywordExtraction;
        }

        public int getRerankCandidateMultiplier() {
            return rerankCandidateMultiplier;
        }

        public void setRerankCandidateMultiplier(int rerankCandidateMultiplier) {
            this.rerankCandidateMultiplier = rerankCandidateMultiplier;
        }
    }

    public static class DemoProperties {
        private boolean asyncIngestEnabled = true;

        public boolean isAsyncIngestEnabled() {
            return asyncIngestEnabled;
        }

        public void setAsyncIngestEnabled(boolean asyncIngestEnabled) {
            this.asyncIngestEnabled = asyncIngestEnabled;
        }
    }

    public static class WorkspaceProperties {
        private String headerName = "X-Workspace-Id";
        private String defaultId = "default";
        private int maxActiveWorkspaces = 32;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }

        public String getDefaultId() {
            return defaultId;
        }

        public void setDefaultId(String defaultId) {
            this.defaultId = defaultId;
        }

        public int getMaxActiveWorkspaces() {
            return maxActiveWorkspaces;
        }

        public void setMaxActiveWorkspaces(int maxActiveWorkspaces) {
            this.maxActiveWorkspaces = maxActiveWorkspaces;
        }
    }

    public enum Type {
        IN_MEMORY,
        POSTGRES,
        POSTGRES_NEO4J
    }

    public static class PostgresProperties {
        private String jdbcUrl;
        private String username;
        private String password;
        private String schema = "lightrag";
        private Integer vectorDimensions;
        private String tablePrefix = "rag_";

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public Integer getVectorDimensions() {
            return vectorDimensions;
        }

        public void setVectorDimensions(Integer vectorDimensions) {
            this.vectorDimensions = vectorDimensions;
        }

        public String getTablePrefix() {
            return tablePrefix;
        }

        public void setTablePrefix(String tablePrefix) {
            this.tablePrefix = tablePrefix;
        }
    }

    public static class Neo4jProperties {
        private String uri;
        private String username = "neo4j";
        private String password;
        private String database = "neo4j";

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }
    }
}
