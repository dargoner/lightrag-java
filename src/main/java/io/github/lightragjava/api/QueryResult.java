package io.github.lightragjava.api;

import io.github.lightragjava.model.CloseableIterator;

import java.util.List;
import java.util.Objects;

public record QueryResult(
    String answer,
    List<Context> contexts,
    List<Reference> references,
    CloseableIterator<String> answerStream,
    boolean streaming
) {
    public QueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        answerStream = Objects.requireNonNull(answerStream, "answerStream");
    }

    public QueryResult(String answer, List<Context> contexts) {
        this(answer, contexts, List.of());
    }

    public QueryResult(String answer, List<Context> contexts, List<Reference> references) {
        this(answer, contexts, references, CloseableIterator.empty(), false);
    }

    public static QueryResult streaming(
        CloseableIterator<String> answerStream,
        List<Context> contexts,
        List<Reference> references
    ) {
        return new QueryResult("", contexts, references, answerStream, true);
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
