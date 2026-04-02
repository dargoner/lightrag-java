package io.github.lightrag.storage.milvus;

import io.milvus.v2.common.ConsistencyLevel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public record MilvusVectorConfig(
    String uri,
    String token,
    String username,
    String password,
    String databaseName,
    String collectionPrefix,
    int vectorDimensions,
    String analyzerType,
    String hybridRanker,
    int hybridRrfK,
    SchemaDriftStrategy schemaDriftStrategy,
    QueryConsistency queryConsistency,
    boolean flushOnWrite
) {
    public static final String DEFAULT_ANALYZER_TYPE = "chinese";
    public static final String DEFAULT_HYBRID_RANKER = "rrf";
    public static final int DEFAULT_HYBRID_RRF_K = 60;
    public static final SchemaDriftStrategy DEFAULT_SCHEMA_DRIFT_STRATEGY = SchemaDriftStrategy.STRICT_FAIL;
    public static final QueryConsistency DEFAULT_QUERY_CONSISTENCY = QueryConsistency.BOUNDED;
    public static final boolean DEFAULT_FLUSH_ON_WRITE = true;

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int MAX_COLLECTION_NAME_LENGTH = 255;

    public MilvusVectorConfig(
        String uri,
        String token,
        String username,
        String password,
        String databaseName,
        String collectionPrefix,
        int vectorDimensions
    ) {
        this(
            uri,
            token,
            username,
            password,
            databaseName,
            collectionPrefix,
            vectorDimensions,
            DEFAULT_ANALYZER_TYPE,
            DEFAULT_HYBRID_RANKER,
            DEFAULT_HYBRID_RRF_K,
            DEFAULT_SCHEMA_DRIFT_STRATEGY,
            DEFAULT_QUERY_CONSISTENCY,
            DEFAULT_FLUSH_ON_WRITE
        );
    }

    public MilvusVectorConfig(
        String uri,
        String token,
        String username,
        String password,
        String databaseName,
        String collectionPrefix,
        int vectorDimensions,
        String analyzerType,
        String hybridRanker,
        int hybridRrfK
    ) {
        this(
            uri,
            token,
            username,
            password,
            databaseName,
            collectionPrefix,
            vectorDimensions,
            analyzerType,
            hybridRanker,
            hybridRrfK,
            DEFAULT_SCHEMA_DRIFT_STRATEGY,
            DEFAULT_QUERY_CONSISTENCY,
            DEFAULT_FLUSH_ON_WRITE
        );
    }

    public MilvusVectorConfig(
        String uri,
        String token,
        String username,
        String password,
        String databaseName,
        String collectionPrefix,
        int vectorDimensions,
        String analyzerType,
        String hybridRanker,
        int hybridRrfK,
        SchemaDriftStrategy schemaDriftStrategy
    ) {
        this(
            uri,
            token,
            username,
            password,
            databaseName,
            collectionPrefix,
            vectorDimensions,
            analyzerType,
            hybridRanker,
            hybridRrfK,
            schemaDriftStrategy,
            DEFAULT_QUERY_CONSISTENCY,
            DEFAULT_FLUSH_ON_WRITE
        );
    }

    public MilvusVectorConfig {
        uri = requireNonBlank(uri, "uri");
        token = normalizeNullable(token);
        username = normalizeNullable(username);
        password = normalizeNullable(password);
        databaseName = requireNonBlank(databaseName, "databaseName");
        collectionPrefix = validatePrefix(collectionPrefix);
        analyzerType = requireNonBlank(defaultIfBlank(analyzerType, DEFAULT_ANALYZER_TYPE), "analyzerType");
        hybridRanker = normalizeHybridRanker(hybridRanker);
        schemaDriftStrategy = schemaDriftStrategy == null ? DEFAULT_SCHEMA_DRIFT_STRATEGY : schemaDriftStrategy;
        queryConsistency = queryConsistency == null ? DEFAULT_QUERY_CONSISTENCY : queryConsistency;
        if (vectorDimensions <= 0) {
            throw new IllegalArgumentException("vectorDimensions must be positive");
        }
        if (hybridRrfK <= 0) {
            throw new IllegalArgumentException("hybridRrfK must be positive");
        }
        if (token == null && (username == null || password == null)) {
            throw new IllegalArgumentException("Either token or username/password must be provided");
        }
    }

    public ConsistencyLevel queryConsistencyLevel() {
        return queryConsistency.toMilvusConsistencyLevel();
    }

    public String collectionName(String workspaceId, String namespace) {
        var normalized = collectionPrefix
            + normalizeSegment(Objects.requireNonNull(workspaceId, "workspaceId"))
            + "_"
            + normalizeSegment(Objects.requireNonNull(namespace, "namespace"));
        if (normalized.length() <= MAX_COLLECTION_NAME_LENGTH) {
            return normalized;
        }
        var digest = shortDigest(normalized);
        var prefixLength = MAX_COLLECTION_NAME_LENGTH - digest.length() - 1;
        return normalized.substring(0, prefixLength) + "_" + digest;
    }

    private static String validatePrefix(String prefix) {
        Objects.requireNonNull(prefix, "collectionPrefix");
        if (prefix.isEmpty()) {
            return prefix;
        }
        if (!IDENTIFIER_PATTERN.matcher(prefix).matches()) {
            throw new IllegalArgumentException("collectionPrefix must be a valid identifier prefix");
        }
        return prefix;
    }

    private static String normalizeSegment(String value) {
        var normalized = requireNonBlank(value, "identifier").replaceAll("[^A-Za-z0-9_]", "_");
        if (Character.isDigit(normalized.charAt(0))) {
            normalized = "_" + normalized;
        }
        return normalized;
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is unavailable", exception);
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static String normalizeHybridRanker(String value) {
        var normalized = requireNonBlank(defaultIfBlank(value, DEFAULT_HYBRID_RANKER), "hybridRanker")
            .toLowerCase();
        if (!normalized.equals(DEFAULT_HYBRID_RANKER) && !normalized.equals("weighted")) {
            throw new IllegalArgumentException("hybridRanker must be one of: rrf, weighted");
        }
        return normalized;
    }

    public enum SchemaDriftStrategy {
        STRICT_REBUILD,
        STRICT_FAIL,
        IGNORE
    }

    public enum QueryConsistency {
        STRONG(ConsistencyLevel.STRONG),
        SESSION(ConsistencyLevel.SESSION),
        BOUNDED(ConsistencyLevel.BOUNDED),
        EVENTUALLY(ConsistencyLevel.EVENTUALLY);

        private final ConsistencyLevel milvusConsistencyLevel;

        QueryConsistency(ConsistencyLevel milvusConsistencyLevel) {
            this.milvusConsistencyLevel = milvusConsistencyLevel;
        }

        ConsistencyLevel toMilvusConsistencyLevel() {
            return milvusConsistencyLevel;
        }
    }
}
