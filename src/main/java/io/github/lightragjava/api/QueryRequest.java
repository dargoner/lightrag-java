package io.github.lightragjava.api;

import java.util.Objects;

public record QueryRequest(
    String query,
    QueryMode mode,
    int topK,
    int chunkTopK,
    String responseType,
    boolean enableRerank
) {
    public static final QueryMode DEFAULT_MODE = QueryMode.MIX;
    public static final int DEFAULT_TOP_K = 10;
    public static final int DEFAULT_CHUNK_TOP_K = 10;
    public static final String DEFAULT_RESPONSE_TYPE = "text";

    public QueryRequest {
        query = Objects.requireNonNull(query, "query");
        mode = Objects.requireNonNull(mode, "mode");
        responseType = Objects.requireNonNull(responseType, "responseType");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (chunkTopK <= 0) {
            throw new IllegalArgumentException("chunkTopK must be positive");
        }
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

        public QueryRequest build() {
            return new QueryRequest(query, mode, topK, chunkTopK, responseType, enableRerank);
        }
    }
}
