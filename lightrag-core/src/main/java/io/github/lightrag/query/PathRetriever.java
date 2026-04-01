package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.QueryContext;
import io.github.lightrag.types.reasoning.PathRetrievalResult;

public interface PathRetriever {
    PathRetrievalResult retrieve(QueryRequest request, QueryContext seedContext);
}
