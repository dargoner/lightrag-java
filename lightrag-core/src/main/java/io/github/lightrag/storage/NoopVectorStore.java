package io.github.lightrag.storage;

import java.util.List;
import java.util.Objects;

/**
 * Vector store for graph-only ingestion: writes are discarded and reads return nothing, so the indexing
 * pipelines skip embedding entirely. Mirrors the upstream {@code NoopVectorDBStorage}.
 *
 * <p>Retrieval modes that query a vector index therefore see no vector hits under this route; switch to a
 * persistent vector store and rebuild the index offline before running those modes.</p>
 */
public final class NoopVectorStore implements VectorStore {
    @Override
    public void saveAll(String namespace, List<VectorRecord> vectors) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(vectors, "vectors");
    }

    @Override
    public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(queryVector, "queryVector");
        return List.of();
    }

    @Override
    public List<VectorRecord> list(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return List.of();
    }
}
