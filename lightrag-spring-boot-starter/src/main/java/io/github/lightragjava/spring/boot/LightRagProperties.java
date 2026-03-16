package io.github.lightragjava.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lightrag")
public class LightRagProperties {
    private final ModelProperties chat = new ModelProperties();
    private final ModelProperties embedding = new ModelProperties();
    private final StorageProperties storage = new StorageProperties();
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
