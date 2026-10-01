package io.github.lightrag.api;

import io.github.lightrag.model.CloseableIterator;

import java.util.List;
import java.util.Objects;

public record QueryResult(
    String answer,
    List<Context> contexts,
    List<Reference> references,
    CloseableIterator<String> answerStream,
    boolean streaming,
    double responseTime,
    boolean llmGenerated
) implements AutoCloseable {
    public QueryResult {
        answer = Objects.requireNonNull(answer, "answer");
        contexts = List.copyOf(Objects.requireNonNull(contexts, "contexts"));
        references = List.copyOf(Objects.requireNonNull(references, "references"));
        answerStream = Objects.requireNonNull(answerStream, "answerStream");
        if (!Double.isFinite(responseTime) || responseTime < 0.0d) {
            throw new IllegalArgumentException("responseTime must be a finite non-negative number of seconds");
        }
    }

    public QueryResult(String answer, List<Context> contexts) {
        this(answer, contexts, List.of());
    }

    public QueryResult(String answer, List<Context> contexts, List<Reference> references) {
        this(answer, contexts, references, 0.0d, false);
    }

    public QueryResult(
        String answer,
        List<Context> contexts,
        List<Reference> references,
        double responseTime,
        boolean llmGenerated
    ) {
        this(answer, contexts, references, CloseableIterator.empty(), false, responseTime, llmGenerated);
    }

    public static QueryResult streaming(
        CloseableIterator<String> answerStream,
        List<Context> contexts,
        List<Reference> references
    ) {
        return streaming(answerStream, contexts, references, true);
    }

    public static QueryResult streaming(
        CloseableIterator<String> answerStream,
        List<Context> contexts,
        List<Reference> references,
        boolean llmGenerated
    ) {
        return new QueryResult("", contexts, references, answerStream, true, 0.0d, llmGenerated);
    }

    @Override
    public void close() {
        answerStream.close();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof QueryResult other)) {
            return false;
        }
        return streaming == other.streaming
            && Double.compare(responseTime, other.responseTime) == 0
            && llmGenerated == other.llmGenerated
            && answer.equals(other.answer)
            && contexts.equals(other.contexts)
            && references.equals(other.references);
    }

    @Override
    public int hashCode() {
        return Objects.hash(answer, contexts, references, streaming, responseTime, llmGenerated);
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
