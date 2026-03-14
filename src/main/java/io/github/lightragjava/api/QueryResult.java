package io.github.lightragjava.api;

import java.util.Objects;

public record QueryResult(String answer) {
    public QueryResult {
        answer = Objects.requireNonNull(answer, "answer");
    }
}
