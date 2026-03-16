package io.github.lightragjava.api;

import java.util.List;
import java.util.Objects;

public record QueryResult(String answer, List<Context> contexts, List<Reference> references) {
    public QueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
    }

    public QueryResult(String answer, List<Context> contexts) {
        this(answer, contexts, List.of());
    }

    public record Context(String sourceId, String text, String referenceId, String source) {
        public Context {
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            text = Objects.requireNonNull(text, "text");
            referenceId = Objects.requireNonNull(referenceId, "referenceId");
            source = Objects.requireNonNull(source, "source");
        }

        public Context(String sourceId, String text) {
            this(sourceId, text, "", "");
        }
    }

    public record Reference(String referenceId, String source) {
        public Reference {
            referenceId = Objects.requireNonNull(referenceId, "referenceId");
            source = Objects.requireNonNull(source, "source");
        }
    }
}
