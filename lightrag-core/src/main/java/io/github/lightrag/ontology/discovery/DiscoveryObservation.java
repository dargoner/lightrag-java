package io.github.lightrag.ontology.discovery;

import java.util.List;
import java.util.Objects;

/**
 * Observation captured while discovering an ontology draft.
 *
 * <p>Support counts are derived from parseable per-chunk proposals only. They
 * describe the sampled chunks, not the whole corpus.
 *
 * @param candidateChunkCount number of chunks offered to the sampling strategy
 * @param sampleCount number of chunks actually sampled
 * @param sampledDocCount number of distinct documents covered by the sample
 * @param supports per-candidate support evidence, ordered by support count
 */
public record DiscoveryObservation(
    int candidateChunkCount,
    int sampleCount,
    int sampledDocCount,
    List<CandidateSupport> supports
) {
    public DiscoveryObservation {
        if (candidateChunkCount < 0 || sampleCount < 0 || sampledDocCount < 0) {
            throw new IllegalArgumentException("observation counts must be non-negative");
        }
        supports = supports == null ? List.of() : List.copyOf(supports);
    }

    public static DiscoveryObservation empty() {
        return new DiscoveryObservation(0, 0, 0, List.of());
    }

    /** Returns the support record of the given candidate kind and identity, if observed. */
    public List<CandidateSupport> supportsOfKind(String kind) {
        Objects.requireNonNull(kind, "kind");
        return supports.stream().filter(item -> kind.equals(item.kind())).toList();
    }
}
