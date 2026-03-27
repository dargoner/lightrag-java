package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.QueryContext;
import io.github.lightragjava.types.reasoning.PathRetrievalResult;

public interface PathRetriever {
    PathRetrievalResult retrieve(QueryRequest request, QueryContext seedContext);
}
