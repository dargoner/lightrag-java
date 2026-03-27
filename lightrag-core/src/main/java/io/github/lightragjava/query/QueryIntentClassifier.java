package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;

public interface QueryIntentClassifier {
    QueryIntent classify(QueryRequest request);
}
