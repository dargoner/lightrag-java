package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record QueryRequest(
    String query,
    QueryMode mode,
    int topK,
    int chunkTopK,
    int maxEntityTokens,
    int maxRelationTokens,
    int maxTotalTokens,
    int maxHop,
    int pathTopK,
    boolean multiHopEnabled,
    String responseType,
    boolean enableRerank,
    boolean onlyNeedContext,
    boolean onlyNeedPrompt,
    boolean includeReferences,
    boolean stream,
    ChatModel modelFunc,
    String userPrompt,
    List<String> hlKeywords,
    List<String> llKeywords,
    List<ChatModel.ChatRequest.ConversationMessage> conversationHistory,
    Map<String, List<String>> metadataFilters,
    List<MetadataCondition> metadataConditions
) {
    public static final QueryMode DEFAULT_MODE = QueryMode.MIX;
    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_CHUNK_TOP_K = 10;
    public static final int DEFAULT_MAX_ENTITY_TOKENS = 6_000;
    public static final int DEFAULT_MAX_RELATION_TOKENS = 8_000;
    public static final int DEFAULT_MAX_TOTAL_TOKENS = 30_000;
    public static final int DEFAULT_MAX_HOP = 2;
    public static final int DEFAULT_PATH_TOP_K = 3;
    public static final String DEFAULT_RESPONSE_TYPE = "Multiple Paragraphs";
    private static final Pattern METADATA_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.]+");

    public QueryRequest {
        query = Objects.requireNonNull(query, "query");
        mode = Objects.requireNonNull(mode, "mode");
        responseType = Objects.requireNonNull(responseType, "responseType");
        userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        hlKeywords = normalizeKeywords(hlKeywords, "hlKeywords");
        llKeywords = normalizeKeywords(llKeywords, "llKeywords");
        conversationHistory = List.copyOf(Objects.requireNonNull(conversationHistory, "conversationHistory"));
        metadataFilters = normalizeMetadataFilters(metadataFilters);
        metadataConditions = normalizeMetadataConditions(metadataConditions);
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (chunkTopK <= 0) {
            throw new IllegalArgumentException("chunkTopK must be positive");
        }
        if (maxEntityTokens <= 0) {
            throw new IllegalArgumentException("maxEntityTokens must be positive");
        }
        if (maxRelationTokens <= 0) {
            throw new IllegalArgumentException("maxRelationTokens must be positive");
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("maxTotalTokens must be positive");
        }
        if (maxHop <= 0) {
            throw new IllegalArgumentException("maxHop must be positive");
        }
        if (pathTopK <= 0) {
            throw new IllegalArgumentException("pathTopK must be positive");
        }
    }

    public QueryRequest(
        String query,
        QueryMode mode,
        int topK,
        int chunkTopK,
        int maxEntityTokens,
        int maxRelationTokens,
        int maxTotalTokens,
        int maxHop,
        int pathTopK,
        boolean multiHopEnabled,
        String responseType,
        boolean enableRerank,
        boolean onlyNeedContext,
        boolean onlyNeedPrompt,
        boolean includeReferences,
        boolean stream,
        ChatModel modelFunc,
        String userPrompt,
        List<String> hlKeywords,
        List<String> llKeywords,
        List<ChatModel.ChatRequest.ConversationMessage> conversationHistory
    ) {
        this(
            query,
            mode,
            topK,
            chunkTopK,
            maxEntityTokens,
            maxRelationTokens,
            maxTotalTokens,
            maxHop,
            pathTopK,
            multiHopEnabled,
            responseType,
            enableRerank,
            onlyNeedContext,
            onlyNeedPrompt,
            includeReferences,
            stream,
            modelFunc,
            userPrompt,
            hlKeywords,
            llKeywords,
            conversationHistory,
            Map.of(),
            List.of()
        );
    }

    public QueryRequest(
        String query,
        QueryMode mode,
        int topK,
        int chunkTopK,
        String responseType,
        boolean enableRerank
    ) {
        this(
            query,
            mode,
            topK,
            chunkTopK,
            DEFAULT_MAX_ENTITY_TOKENS,
            DEFAULT_MAX_RELATION_TOKENS,
            DEFAULT_MAX_TOTAL_TOKENS,
            DEFAULT_MAX_HOP,
            DEFAULT_PATH_TOP_K,
            true,
            responseType,
            enableRerank,
            false,
            false,
            false,
            false,
            null,
            "",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of()
        );
    }

    public QueryRequest(
        String query,
        QueryMode mode,
        int topK,
        int chunkTopK,
        String responseType,
        boolean enableRerank,
        String userPrompt,
        List<ChatModel.ChatRequest.ConversationMessage> conversationHistory
    ) {
        this(
            query,
            mode,
            topK,
            chunkTopK,
            DEFAULT_MAX_ENTITY_TOKENS,
            DEFAULT_MAX_RELATION_TOKENS,
            DEFAULT_MAX_TOTAL_TOKENS,
            DEFAULT_MAX_HOP,
            DEFAULT_PATH_TOP_K,
            true,
            responseType,
            enableRerank,
            false,
            false,
            false,
            false,
            null,
            userPrompt,
            List.of(),
            List.of(),
            conversationHistory,
            Map.of(),
            List.of()
        );
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String query;
        private QueryMode mode = DEFAULT_MODE;
        private int topK = DEFAULT_TOP_K;
        private int chunkTopK = DEFAULT_CHUNK_TOP_K;
        private int maxEntityTokens = DEFAULT_MAX_ENTITY_TOKENS;
        private int maxRelationTokens = DEFAULT_MAX_RELATION_TOKENS;
        private int maxTotalTokens = DEFAULT_MAX_TOTAL_TOKENS;
        private int maxHop = DEFAULT_MAX_HOP;
        private int pathTopK = DEFAULT_PATH_TOP_K;
        private boolean multiHopEnabled = true;
        private String responseType = DEFAULT_RESPONSE_TYPE;
        private boolean enableRerank = true;
        private boolean onlyNeedContext;
        private boolean onlyNeedPrompt;
        private boolean includeReferences;
        private boolean stream;
        private ChatModel modelFunc;
        private String userPrompt = "";
        private List<String> hlKeywords = List.of();
        private List<String> llKeywords = List.of();
        private List<ChatModel.ChatRequest.ConversationMessage> conversationHistory = List.of();
        private Map<String, List<String>> metadataFilters = Map.of();
        private List<MetadataCondition> metadataConditions = List.of();

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder mode(QueryMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder topK(int topK) {
            this.topK = topK;
            return this;
        }

        public Builder chunkTopK(int chunkTopK) {
            this.chunkTopK = chunkTopK;
            return this;
        }

        public Builder maxEntityTokens(int maxEntityTokens) {
            this.maxEntityTokens = maxEntityTokens;
            return this;
        }

        public Builder maxRelationTokens(int maxRelationTokens) {
            this.maxRelationTokens = maxRelationTokens;
            return this;
        }

        public Builder maxTotalTokens(int maxTotalTokens) {
            this.maxTotalTokens = maxTotalTokens;
            return this;
        }

        public Builder maxHop(int maxHop) {
            this.maxHop = maxHop;
            return this;
        }

        public Builder pathTopK(int pathTopK) {
            this.pathTopK = pathTopK;
            return this;
        }

        public Builder multiHopEnabled(boolean multiHopEnabled) {
            this.multiHopEnabled = multiHopEnabled;
            return this;
        }

        public Builder responseType(String responseType) {
            this.responseType = responseType;
            return this;
        }

        public Builder enableRerank(boolean enableRerank) {
            this.enableRerank = enableRerank;
            return this;
        }

        public Builder onlyNeedContext(boolean onlyNeedContext) {
            this.onlyNeedContext = onlyNeedContext;
            return this;
        }

        public Builder onlyNeedPrompt(boolean onlyNeedPrompt) {
            this.onlyNeedPrompt = onlyNeedPrompt;
            return this;
        }

        public Builder includeReferences(boolean includeReferences) {
            this.includeReferences = includeReferences;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder modelFunc(ChatModel modelFunc) {
            this.modelFunc = modelFunc;
            return this;
        }

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
            return this;
        }

        public Builder hlKeywords(List<String> hlKeywords) {
            this.hlKeywords = hlKeywords;
            return this;
        }

        public Builder llKeywords(List<String> llKeywords) {
            this.llKeywords = llKeywords;
            return this;
        }

        public Builder conversationHistory(List<ChatModel.ChatRequest.ConversationMessage> conversationHistory) {
            this.conversationHistory = conversationHistory;
            return this;
        }

        public Builder metadataFilters(Map<String, ?> metadataFilters) {
            this.metadataFilters = normalizeMetadataFilters(Objects.requireNonNull(metadataFilters, "metadataFilters"));
            return this;
        }

        public Builder metadataConditions(List<MetadataCondition> metadataConditions) {
            this.metadataConditions = Objects.requireNonNull(metadataConditions, "metadataConditions");
            return this;
        }

        public QueryRequest build() {
            return new QueryRequest(
                query,
                mode,
                topK,
                chunkTopK,
                maxEntityTokens,
                maxRelationTokens,
                maxTotalTokens,
                maxHop,
                pathTopK,
                multiHopEnabled,
                responseType,
                enableRerank,
                onlyNeedContext,
                onlyNeedPrompt,
                includeReferences,
                stream,
                modelFunc,
                userPrompt,
                hlKeywords,
                llKeywords,
                conversationHistory,
                metadataFilters,
                metadataConditions
            );
        }
    }

    private static Map<String, List<String>> normalizeMetadataFilters(Map<String, ?> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return Map.of();
        }

        var normalized = new LinkedHashMap<String, List<String>>();
        for (var entry : metadataFilters.entrySet()) {
            var key = Objects.requireNonNull(entry.getKey(), "metadataFilters key").trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("metadataFilters key must not be empty");
            }
            if (!METADATA_KEY_PATTERN.matcher(key).matches()) {
                throw new IllegalArgumentException("metadataFilters key must match [A-Za-z0-9_.]+: " + key);
            }

            mergeNormalizedMetadataFilterValues(normalized, key, entry.getValue());
        }

        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static void mergeNormalizedMetadataFilterValues(Map<String, List<String>> normalized, String key, Object value) {
        var collector = new LinkedHashSet<String>();
        collectMetadataFilterValues(value, collector);
        if (collector.isEmpty()) {
            return;
        }

        var existing = normalized.get(key);
        if (existing == null || existing.isEmpty()) {
            normalized.put(key, List.copyOf(collector));
            return;
        }

        var merged = new LinkedHashSet<String>(existing);
        merged.addAll(collector);
        normalized.put(key, List.copyOf(merged));
    }

    private static void collectMetadataFilterValues(Object value, LinkedHashSet<String> collector) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (var element : iterable) {
                collectMetadataFilterValues(element, collector);
            }
            return;
        }
        var clazz = value.getClass();
        if (clazz.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                collectMetadataFilterValues(Array.get(value, i), collector);
            }
            return;
        }
        var trimmed = value.toString().trim();
        if (!trimmed.isEmpty()) {
            collector.add(trimmed);
        }
    }

    private static List<MetadataCondition> normalizeMetadataConditions(List<MetadataCondition> metadataConditions) {
        if (metadataConditions == null || metadataConditions.isEmpty()) {
            return List.of();
        }

        return List.copyOf(metadataConditions.stream()
            .map(condition -> {
                if (condition == null) {
                    throw new IllegalArgumentException("metadataConditions entry");
                }
                return normalizeMetadataCondition(condition);
            })
            .toList());
    }

    private static MetadataCondition normalizeMetadataCondition(MetadataCondition condition) {
        var value = condition.value();
        return switch (condition.operator()) {
            case IN -> normalizeInCondition(condition, value);
            case EQ -> normalizeScalarConditionPayload(condition, value);
            case GT, GTE, LT, LTE -> normalizeNumericCondition(condition, value);
            case BEFORE, AFTER -> normalizeDateCondition(condition, value);
        };
    }

    private static MetadataCondition normalizeInCondition(MetadataCondition condition, Object value) {
        if (!(value instanceof Iterable<?>) && !value.getClass().isArray()) {
            throw new IllegalArgumentException("metadataConditions IN value must be iterable or array");
        }

        var collector = new LinkedHashSet<String>();
        collectMetadataFilterValues(value, collector);
        if (collector.isEmpty()) {
            throw new IllegalArgumentException("metadataConditions IN value must contain at least one non-empty element");
        }
        return new MetadataCondition(condition.field(), condition.operator(), List.copyOf(collector));
    }

    private static MetadataCondition normalizeScalarConditionPayload(MetadataCondition condition, Object value) {
        ensureScalarConditionPayload(condition, value);
        return condition;
    }

    private static MetadataCondition normalizeNumericCondition(MetadataCondition condition, Object value) {
        ensureScalarConditionPayload(condition, value);
        var normalized = normalizeScalarConditionLiteral(condition.operator().name(), value);
        try {
            new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                "metadataConditions " + condition.operator() + " value must be numeric: " + normalized,
                ex
            );
        }
        return condition;
    }

    private static MetadataCondition normalizeDateCondition(MetadataCondition condition, Object value) {
        ensureScalarConditionPayload(condition, value);
        var normalized = normalizeScalarConditionLiteral(condition.operator().name(), value);
        try {
            parseInstant(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "metadataConditions " + condition.operator() + " value must be a supported date literal: " + normalized,
                ex
            );
        }
        return condition;
    }

    private static void ensureScalarConditionPayload(MetadataCondition condition, Object value) {
        if (value == null || value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray()) {
            throw new IllegalArgumentException(
                "metadataConditions " + condition.operator() + " value must not be multi-value/object payload"
            );
        }
    }

    private static String normalizeScalarConditionLiteral(String operatorName, Object value) {
        var normalized = value.toString().trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("metadataConditions " + operatorName + " value must not be blank");
        }
        return normalized;
    }

    private static Instant parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return Instant.ofEpochMilli(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
        }

        throw new IllegalArgumentException("metadata date value must be parseable: " + value);
    }

    private static List<String> normalizeKeywords(List<String> keywords, String fieldName) {
        return List.copyOf(Objects.requireNonNull(keywords, fieldName).stream()
            .map(keyword -> Objects.requireNonNull(keyword, fieldName + " entry"))
            .map(String::trim)
            .filter(keyword -> !keyword.isEmpty())
            .toList());
    }
}
