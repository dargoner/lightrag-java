package io.github.lightragjava.query;

import io.github.lightragjava.api.QueryRequest;
import io.github.lightragjava.types.reasoning.PathRetrievalResult;
import io.github.lightragjava.types.reasoning.ReasoningPath;

import java.util.List;

public interface PathScorer {
    List<ReasoningPath> rerank(QueryRequest request, PathRetrievalResult retrievalResult);
}
