package io.github.lightrag.ontology.discovery;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.types.Chunk;

import java.util.List;
import java.util.Objects;

/**
 * Request to discover an ontology draft from a corpus sample.
 *
 * @param chatModel    the LLM used to propose ontology elements
 * @param chunks       the sample chunks to analyze
 * @param baseOntology optional baseline ontology; discovered elements that
 *                     conflict with it are reported as conflicts instead of
 *                     being silently merged
 * @param options      discovery options such as max proposals per chunk
 */
public record DiscoveryRequest(
    ChatModel chatModel,
    List<Chunk> chunks,
    Ontology baseOntology,
    DiscoveryOptions options
) {
    public DiscoveryRequest {
        Objects.requireNonNull(chatModel, "chatModel");
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        baseOntology = baseOntology == null ? new Ontology(null, null, null, null) : baseOntology;
        options = options == null ? DiscoveryOptions.defaults() : options;
    }

    public DiscoveryRequest(ChatModel chatModel, List<Chunk> chunks, DiscoveryOptions options) {
        this(chatModel, chunks, null, options);
    }

    public record DiscoveryOptions(
        int maxEntitiesPerChunk,
        int maxRelationsPerChunk,
        int maxPropertiesPerType,
        int sampleSize,
        int maxRetries,
        long retryDelayMillis,
        long seed
    ) {
        public DiscoveryOptions {
            if (maxEntitiesPerChunk < 1) {
                throw new IllegalArgumentException("maxEntitiesPerChunk must be positive");
            }
            if (maxRelationsPerChunk < 1) {
                throw new IllegalArgumentException("maxRelationsPerChunk must be positive");
            }
            if (maxPropertiesPerType < 0) {
                throw new IllegalArgumentException("maxPropertiesPerType must not be negative");
            }
            if (sampleSize < 0) {
                throw new IllegalArgumentException("sampleSize must not be negative");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative");
            }
            if (retryDelayMillis < 0) {
                throw new IllegalArgumentException("retryDelayMillis must not be negative");
            }
        }

        public DiscoveryOptions(
            int maxEntitiesPerChunk,
            int maxRelationsPerChunk,
            int maxPropertiesPerType,
            int sampleSize,
            int maxRetries,
            long retryDelayMillis
        ) {
            this(maxEntitiesPerChunk, maxRelationsPerChunk, maxPropertiesPerType, sampleSize, maxRetries, retryDelayMillis, 0L);
        }

        public static DiscoveryOptions defaults() {
            return new DiscoveryOptions(10, 10, 5, 5, 1, 0L, 0L);
        }
    }
}
