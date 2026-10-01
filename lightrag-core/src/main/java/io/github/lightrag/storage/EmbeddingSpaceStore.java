package io.github.lightrag.storage;

import java.util.Objects;
import java.util.Optional;

/**
 * Records which embedding space (model identity and vector dimensions) the vectors of a workspace were
 * produced with, so a later write from a different space is refused instead of silently mixing vectors
 * that share a dimension but come from different models. Mirrors the upstream vector-space marker
 * ({@code lightrag/kg/vector_space.py}).
 */
public interface EmbeddingSpaceStore {
    void save(Marker marker);

    Optional<Marker> load();

    void delete();

    record Marker(String modelIdentity, int dimensions, String recordedAt) {
        public Marker {
            modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity");
            if (modelIdentity.isBlank()) {
                throw new IllegalArgumentException("modelIdentity must not be blank");
            }
            if (dimensions < 0) {
                throw new IllegalArgumentException("dimensions must not be negative");
            }
            recordedAt = recordedAt == null ? "" : recordedAt;
        }
    }
}
