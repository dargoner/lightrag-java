package io.github.lightragjava.api;

import io.github.lightragjava.model.ChatModel;

import java.util.List;
import java.util.Objects;

public record QueryRequest(
    String query,
    QueryMode mode,
    int topK,
    int chunkTopK,
    int maxEntityTokens,
    int maxRelationTokens,
    int maxTotalTokens,
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
    public static final QueryMode DEFAULT_MODE = QueryMode.MIX;
    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_CHUNK_TOP_K = 10;
    public static final int DEFAULT_MAX_ENTITY_TOKENS = 6_000;
    public static final int DEFAULT_MAX_RELATION_TOKENS = 8_000;
    public static final int DEFAULT_MAX_TOTAL_TOKENS = 30_000;
    public static final String DEFAULT_RESPONSE_TYPE = "Multiple Paragraphs";

    public QueryRequest {
        query = Objects.requireNonNull(query, "query");
        mode = Objects.requireNonNull(mode, "mode");
        responseType = Objects.requireNonNull(responseType, "responseType");
        userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        hlKeywords = normalizeKeywords(hlKeywords, "hlKeywords");
        llKeywords = normalizeKeywords(llKeywords, "llKeywords");
        conversationHistory = List.copyOf(Objects.requireNonNull(conversationHistory, "conversationHistory"));
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
            conversationHistory
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

        public QueryRequest build() {
            return new QueryRequest(
                query,
                mode,
                topK,
                chunkTopK,
                maxEntityTokens,
                maxRelationTokens,
                maxTotalTokens,
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
                conversationHistory
            );
        }
    }

    private static List<String> normalizeKeywords(List<String> keywords, String fieldName) {
        return List.copyOf(Objects.requireNonNull(keywords, fieldName).stream()
            .map(keyword -> Objects.requireNonNull(keyword, fieldName + " entry"))
            .map(String::trim)
            .filter(keyword -> !keyword.isEmpty())
            .toList());
    }
}
