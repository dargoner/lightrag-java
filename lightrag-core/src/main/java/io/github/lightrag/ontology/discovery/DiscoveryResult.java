package io.github.lightrag.ontology.discovery;

import io.github.lightrag.ontology.Ontology;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of ontology discovery.
 *
 * @param draft      the proposed ontology draft. It is not persisted and must
 *                   be reviewed before it can be applied.
 * @param failures   structured failure messages for chunks whose proposals
 *                   could not be parsed or validated.
 * @param conflicts  human-readable conflict descriptions when the draft
 *                   contradicts the baseline ontology.
 * @param fingerprint a stable fingerprint of the draft for cache/audit keys.
 */
public record DiscoveryResult(
    Ontology draft,
    List<String> failures,
    List<String> conflicts,
    String fingerprint
) {
    public DiscoveryResult {
        Objects.requireNonNull(draft, "draft");
        failures = failures == null ? List.of() : List.copyOf(failures);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        fingerprint = fingerprint == null ? "" : fingerprint;
    }

    /** Returns a result for an empty draft. */
    public static DiscoveryResult empty() {
        return new DiscoveryResult(
            new Ontology(List.of(), List.of(), null, Map.of()),
            List.of(),
            List.of(),
            ""
        );
    }
}
