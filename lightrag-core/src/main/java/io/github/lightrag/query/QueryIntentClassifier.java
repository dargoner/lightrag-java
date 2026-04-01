package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;

public interface QueryIntentClassifier {
    QueryIntent classify(QueryRequest request);
}
