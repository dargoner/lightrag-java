package io.github.lightrag.ontology.discovery;

import io.github.lightrag.ontology.Identity;
import io.github.lightrag.types.Chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Strategy for selecting which chunks feed ontology discovery.
 */
@FunctionalInterface
public interface DiscoverySampleStrategy {

    /**
     * Selects a sample of chunks from the supplied population.
     *
     * @param population all available chunks
     * @param sampleSize the requested sample size
     * @return the selected chunks
     */
    List<Chunk> select(List<Chunk> population, int sampleSize);

    /**
     * Stable identifier of the sampling strategy, folded into sample fingerprints.
     *
     * <p>Implementations must return a fixed, JVM-independent string (for example
     * {@code "stratifiedByDocument/v1"}); runtime class names or lambda identities
     * are not stable across processes. Custom lambda strategies inherit the
     * {@code "custom"} default and are therefore reproducible given the same seed.
     * Bump the version suffix whenever the sampling algorithm changes so that
     * fingerprints change with it.
     */
    default String strategyId() {
        return "custom";
    }

    /** Random sampling with a fixed seed for reproducibility. */
    static DiscoverySampleStrategy random(long seed) {
        return new DiscoverySampleStrategy() {
            @Override
            public List<Chunk> select(List<Chunk> population, int sampleSize) {
                Objects.requireNonNull(population, "population");
                if (sampleSize <= 0 || population.isEmpty()) {
                    return List.of();
                }
                var copy = List.copyOf(population);
                var shuffled = new ArrayList<>(copy);
                Collections.shuffle(shuffled, new Random(seed));
                return List.copyOf(shuffled.subList(0, Math.min(sampleSize, shuffled.size())));
            }

            @Override
            public String strategyId() {
                return "random/v1";
            }
        };
    }

    /** Takes the first N chunks in order. */
    static DiscoverySampleStrategy firstN() {
        return new DiscoverySampleStrategy() {
            @Override
            public List<Chunk> select(List<Chunk> population, int sampleSize) {
                Objects.requireNonNull(population, "population");
                if (sampleSize <= 0 || population.isEmpty()) {
                    return List.of();
                }
                return List.copyOf(population.subList(0, Math.min(sampleSize, population.size())));
            }

            @Override
            public String strategyId() {
                return "firstN/v1";
            }
        };
    }

    /**
     * Stratified sampling that picks roughly evenly across documents.
     *
     * <p>Chunks are grouped by document id, deduplicated by content, and
     * sampled with a per-document random source so that no single document
     * dominates the sample. Each document is guaranteed at least one chunk when
     * the requested sample size permits, and remaining slots are distributed
     * proportionally to the number of available chunks in each document. The
     * per-document RNG uses the stable formula
     * {@code seed * 0x9E3779B97F4A7C15L ^ identityId(docId).hashCode()} so that
     * adding or removing chunks in one document does not perturb the sample
     * drawn from another document.
     */
    static DiscoverySampleStrategy stratifiedByDocument(long seed) {
        return new DiscoverySampleStrategy() {
            @Override
            public List<Chunk> select(List<Chunk> population, int sampleSize) {
                Objects.requireNonNull(population, "population");
                if (sampleSize <= 0 || population.isEmpty()) {
                    return List.of();
                }
                Map<String, List<Chunk>> byDocument = new LinkedHashMap<>();
                for (Chunk chunk : population) {
                    byDocument.computeIfAbsent(chunk.documentId(), ignored -> new ArrayList<>()).add(chunk);
                }

                Map<String, List<Chunk>> pools = new LinkedHashMap<>();
                for (Map.Entry<String, List<Chunk>> entry : byDocument.entrySet()) {
                    List<Chunk> shuffled = new ArrayList<>(deduplicateByContent(entry.getValue()));
                    long perDocumentSeed = seed * 0x9E3779B97F4A7C15L ^ Identity.identityId(entry.getKey()).hashCode();
                    Collections.shuffle(shuffled, new Random(perDocumentSeed));
                    pools.put(entry.getKey(), shuffled);
                }

                List<Chunk> result = new ArrayList<>();
                int documentCount = pools.size();
                int minDocuments = Math.min(documentCount, sampleSize);

                // Guarantee coverage from as many documents as possible.
                for (List<Chunk> chunks : pools.values()) {
                    if (result.size() >= minDocuments) {
                        break;
                    }
                    if (!chunks.isEmpty()) {
                        result.add(chunks.remove(0));
                    }
                }

                // Distribute the remaining slots proportionally to remaining chunks.
                int remaining = sampleSize - result.size();
                while (remaining > 0) {
                    int totalRemaining = pools.values().stream().mapToInt(List::size).sum();
                    if (totalRemaining == 0) {
                        break;
                    }
                    boolean added = false;
                    for (List<Chunk> chunks : pools.values()) {
                        if (remaining <= 0) {
                            break;
                        }
                        if (chunks.isEmpty()) {
                            continue;
                        }
                        int quota = Math.max(1, (int) Math.round((double) chunks.size() / totalRemaining * remaining));
                        quota = Math.min(quota, chunks.size());
                        for (int i = 0; i < quota; i++) {
                            result.add(chunks.remove(0));
                        }
                        remaining -= quota;
                        added = true;
                    }
                    if (!added) {
                        break;
                    }
                }
                return List.copyOf(result);
            }

            @Override
            public String strategyId() {
                return "stratifiedByDocument/v1";
            }
        };
    }

    private static List<Chunk> deduplicateByContent(List<Chunk> chunks) {
        var seen = new java.util.LinkedHashSet<String>();
        var result = new ArrayList<Chunk>();
        for (Chunk chunk : chunks) {
            String text = chunk.text();
            if (text == null) {
                text = "";
            }
            if (seen.add(text)) {
                result.add(chunk);
            }
        }
        return result;
    }
}
