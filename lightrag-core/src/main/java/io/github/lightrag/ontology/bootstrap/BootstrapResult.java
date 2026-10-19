package io.github.lightrag.ontology.bootstrap;

import io.github.lightrag.ontology.Ontology;

import java.util.List;
import java.util.Objects;

/**
 * Result of a deterministic bootstrap run.
 *
 * <p>The draft is a complete candidate ontology (base definitions plus inferred
 * additions), but it is never applied automatically, never written to storage,
 * and never assigned a version. With a base ontology the host must submit it
 * through the {@code EVOLUTION_REPLACE} approval path.
 *
 * @param draft            complete candidate; never {@code null}, version {@code null}
 * @param conflicts        deterministic conflicts, sorted by owner/item/detail
 * @param warnings         deterministic warnings, sorted naturally
 * @param sampleFingerprint SHA-256 over algorithm version, normalized samples,
 *                          effective options, and the canonical base ontology
 * @param algorithmVersion bootstrap algorithm version
 * @param stats            counters describing the run
 */
public record BootstrapResult(
    Ontology draft,
    List<BootstrapConflict> conflicts,
    List<String> warnings,
    String sampleFingerprint,
    int algorithmVersion,
    BootstrapStats stats
) {
    public BootstrapResult {
        Objects.requireNonNull(draft, "draft");
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        sampleFingerprint = sampleFingerprint == null ? "" : sampleFingerprint;
        Objects.requireNonNull(stats, "stats");
    }
}
