package io.github.lightragjava.api;

import io.github.lightragjava.model.ChatModel;

import java.util.List;
import java.util.Objects;

public record QueryRequest(
    String query,
    QueryMode mode,
    int topK,
    int chunkTopK,
    String responseType,
    boolean enableRerank,
    boolean onlyNeedContext,
    boolean onlyNeedPrompt,
    String userPrompt,
    List<ChatModel.ChatRequest.ConversationMessage> conversationHistory
) {
    public static final QueryMode DEFAULT_MODE = QueryMode.MIX;
    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_CHUNK_TOP_K = 10;
    public static final String DEFAULT_RESPONSE_TYPE = "text";

    public QueryRequest {
        query = Objects.requireNonNull(query, "query");
        mode = Objects.requireNonNull(mode, "mode");
        responseType = Objects.requireNonNull(responseType, "responseType");
        userPrompt = Objects.requireNonNull(userPrompt, "userPrompt");
        conversationHistory = List.copyOf(Objects.requireNonNull(conversationHistory, "conversationHistory"));
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (chunkTopK <= 0) {
            throw new IllegalArgumentException("chunkTopK must be positive");
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
        this(query, mode, topK, chunkTopK, responseType, enableRerank, false, false, "", List.of());
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
        this(query, mode, topK, chunkTopK, responseType, enableRerank, false, false, userPrompt, conversationHistory);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String query;
        private QueryMode mode = DEFAULT_MODE;
        private int topK = DEFAULT_TOP_K;
        private int chunkTopK = DEFAULT_CHUNK_TOP_K;
        private String responseType = DEFAULT_RESPONSE_TYPE;
        private boolean enableRerank = true;
        private boolean onlyNeedContext;
        private boolean onlyNeedPrompt;
        private String userPrompt = "";
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

        public Builder userPrompt(String userPrompt) {
            this.userPrompt = userPrompt;
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
                responseType,
                enableRerank,
                onlyNeedContext,
                onlyNeedPrompt,
                userPrompt,
                conversationHistory
            );
        }
    }
}
