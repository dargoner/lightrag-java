package io.github.lightragjava.api;

import java.util.Objects;

public record QueryRequest(String query, QueryMode mode) {
    public QueryRequest {
        query = Objects.requireNonNull(query, "query");
        mode = Objects.requireNonNull(mode, "mode");
    }
}
