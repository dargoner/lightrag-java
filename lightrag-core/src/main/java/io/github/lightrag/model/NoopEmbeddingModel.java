package io.github.lightrag.model;

import java.util.List;
import java.util.Objects;

/**
 * Embedding model for the {@link io.github.lightrag.storage.NoopVectorStore} route: every input maps to a
 * zero-length vector, so no real embedding request is ever issued. Pair it with
 * {@code LightRagBuilder.noopVectorStore(true)}; using it with a persistent vector store would persist
 * unusable vectors.
 */
public final class NoopEmbeddingModel implements EmbeddingModel {
    @Override
    public List<List<Double>> embedAll(List<String> texts) {
        var inputs = List.copyOf(Objects.requireNonNull(texts, "texts"));
        return inputs.stream().map(ignored -> List.<Double>of()).toList();
    }
}
