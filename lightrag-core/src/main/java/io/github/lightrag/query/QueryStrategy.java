package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;

public interface QueryStrategy {
    QueryContext retrieve(QueryRequest request);
}
