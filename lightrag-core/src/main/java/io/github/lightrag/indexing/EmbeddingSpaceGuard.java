package io.github.lightrag.indexing;

import io.github.lightrag.exception.VectorSpaceMismatchException;
import io.github.lightrag.storage.AtomicStorageProvider.AtomicStorageView;
import io.github.lightrag.storage.EmbeddingSpaceStore;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Verifies that the embedding space of a pending vector write matches the space the workspace vectors were
 * recorded with, and records the marker on the first write. Mirrors the upstream vector-space refusal
 * ({@code lightrag/kg/vector_space.py}): the first real write is early enough, so no probe embedding is
 * issued.
 */
final class EmbeddingSpaceGuard {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingSpaceGuard.class);
    private static final AtomicBoolean unsupportedStoreLogged = new AtomicBoolean();

    private EmbeddingSpaceGuard() {
    }

    static void verifyOrRecord(AtomicStorageView storage, String modelIdentity, List<VectorStore.VectorRecord> vectors) {
        var view = Objects.requireNonNull(storage, "storage");
        verifyOrRecord(view::embeddingSpaceStore, modelIdentity, vectors);
    }

    static void verifyOrRecord(StorageProvider provider, String modelIdentity, List<VectorStore.VectorRecord> vectors) {
        var source = Objects.requireNonNull(provider, "provider");
        verifyOrRecord(source::embeddingSpaceStore, modelIdentity, vectors);
    }

    private static void verifyOrRecord(
        Supplier<EmbeddingSpaceStore> storeSupplier,
        String modelIdentity,
        List<VectorStore.VectorRecord> vectors
    ) {
        var batch = List.copyOf(Objects.requireNonNull(vectors, "vectors"));
        if (batch.isEmpty()) {
            return;
        }
        EmbeddingSpaceStore spaceStore;
        try {
            spaceStore = storeSupplier.get();
        } catch (UnsupportedOperationException exception) {
            if (!Objects.equals(exception.getMessage(), StorageProvider.EMBEDDING_SPACE_STORE_UNSUPPORTED_MESSAGE)) {
                throw exception;
            }
            if (unsupportedStoreLogged.compareAndSet(false, true)) {
                log.warn(
                    "Embedding space refusal is unavailable because the storage provider has no embedding space store; "
                        + "vectors from different models with the same dimension will be mixed silently"
                );
            }
            return;
        }
        var identity = modelIdentity == null || modelIdentity.isBlank() ? "unknown" : modelIdentity;
        var dimensions = batch.get(0).vector().size();
        var existing = spaceStore.load();
        if (existing.isPresent()) {
            var marker = existing.get();
            if (!marker.modelIdentity().equals(identity) || marker.dimensions() != dimensions) {
                throw new VectorSpaceMismatchException(
                    "Embedding space mismatch for the current workspace: stored vectors were recorded with "
                        + describe(marker.modelIdentity(), marker.dimensions())
                        + " but the configured embedding model produces "
                        + describe(identity, dimensions)
                        + "; rebuild the vector index with the rebuild-vdb tool (or clear the embedding space marker) "
                        + "before writing"
                );
            }
            return;
        }
        spaceStore.save(new EmbeddingSpaceStore.Marker(identity, dimensions, Instant.now().toString()));
    }

    private static String describe(String identity, int dimensions) {
        return "'%s' (%d dimensions)".formatted(identity, dimensions);
    }
}
