package io.github.lightrag.query;

import io.github.lightrag.api.QueryRequest;

import java.util.Locale;
import java.util.Objects;

public final class RuleBasedQueryIntentClassifier implements QueryIntentClassifier {
    @Override
    public QueryIntent classify(QueryRequest request) {
        var normalized = Objects.requireNonNull(request, "request").query().strip().toLowerCase(Locale.ROOT);
        if (normalized.contains("关系") || normalized.contains("relationship")) {
            return QueryIntent.RELATION;
        }
        if (normalized.contains("通过")
            || normalized.contains("经过")
            || normalized.contains("间接")
            || normalized.contains("多跳")
            || normalized.contains("through")
            || normalized.contains("via")
            || normalized.contains("indirect")
            || normalized.contains("multi-hop")
            || normalized.contains("multihop")
            || (normalized.contains("先") && normalized.contains("再"))
            || (normalized.contains("first") && normalized.contains("then"))) {
            return QueryIntent.MULTI_HOP;
        }
        return QueryIntent.FACT;
    }
}
