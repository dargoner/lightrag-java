package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;
import io.github.lightrag.types.reasoning.PathRetrievalResult;
import io.github.lightrag.types.reasoning.ReasoningPath;

import java.util.List;

public interface PathScorer {
    List<ReasoningPath> rerank(QueryRequest request, PathRetrievalResult retrievalResult);
}
