package io.github.lightragjava.api;

import java.util.List;
import java.util.Objects;

public record QueryResult(String answer, List<Context> contexts) {
    public QueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
    }

    public record Context(String sourceId, String text) {
        public Context {
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            text = Objects.requireNonNull(text, "text");
        }
    }
}
