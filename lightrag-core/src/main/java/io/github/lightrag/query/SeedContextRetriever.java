package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;

public interface SeedContextRetriever {
    QueryContext retrieve(QueryRequest request);
}
