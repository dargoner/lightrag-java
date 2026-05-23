package io.github.lightrag.storage.arcadedb;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public record ArcadeDbConfig(
    URI baseUrl,
    String database,
    String username,
    String password,
    int vectorDimensions,
    Duration timeout,
    boolean initSchema
) {
    public ArcadeDbConfig {
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        database = requireNonBlank(database, "database");
        username = requireNonBlank(username, "username");
        password = Objects.requireNonNull(password, "password");
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public static final class Builder {
        private URI baseUrl = URI.create("http://localhost:2480");
        private String database = "lightrag";
        private String username = "root";
        private String password = "";
        private int vectorDimensions = 1536;
        private Duration timeout = Duration.ofSeconds(30);
        private boolean initSchema = true;

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder vectorDimensions(int vectorDimensions) {
            this.vectorDimensions = vectorDimensions;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder initSchema(boolean initSchema) {
            this.initSchema = initSchema;
            return this;
        }

        public ArcadeDbConfig build() {
            return new ArcadeDbConfig(baseUrl, database, username, password, vectorDimensions, timeout, initSchema);
        }
    }
}
