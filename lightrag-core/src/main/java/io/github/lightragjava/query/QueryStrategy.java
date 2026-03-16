package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.QueryContext;

public interface QueryStrategy {
    QueryContext retrieve(QueryRequest request);
}
