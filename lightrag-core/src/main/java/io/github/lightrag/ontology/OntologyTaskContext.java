package io.github.lightrag.ontology;

import io.github.lightrag.api.GraphExtractionOptions;

import java.util.Objects;

/**
 * Immutable task-level snapshot of ontology-driven extraction configuration.
 *
 * <p>Capturing the snapshot at task submit time guarantees that the same
 * ontology, overlay, and validator are used throughout the task even if the
 * workspace-level configuration changes while the task is queued or running.
 */
public record OntologyTaskContext(
    GraphExtractionOptions graphExtractionOptions,
    Ontology ontology,
    OntologyExtractionOverlay overlay,
    boolean dropOnUnknownKeyword,
    boolean bound
) {
    public OntologyTaskContext {
        graphExtractionOptions = graphExtractionOptions == null
            ? GraphExtractionOptions.defaults()
            : graphExtractionOptions;
        ontology = ontology == null ? new Ontology(null, null, null, null) : ontology;
        overlay = overlay == null ? OntologyExtractionOverlay.empty() : overlay;
    }

    /**
     * Convenience constructor for explicitly supplied contexts. Explicitly supplied
     * snapshots count as bound even when their values happen to equal the defaults,
     * so no caller re-resolves workspace configuration for them.
     */
    public OntologyTaskContext(
        GraphExtractionOptions graphExtractionOptions,
        Ontology ontology,
        OntologyExtractionOverlay overlay,
        boolean dropOnUnknownKeyword
    ) {
        this(graphExtractionOptions, ontology, overlay, dropOnUnknownKeyword, true);
    }

    /** Returns an unbound context with default extraction options and an empty ontology. */
    public static OntologyTaskContext empty() {
        return new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, false, false);
    }

    /**
     * Returns true when no snapshot has been bound yet. A bound snapshot that is
     * value-equal to {@link #empty()} is not empty: the configuration was already
     * resolved once and must not be re-resolved from the provider.
     */
    public boolean isEmpty() {
        return !bound;
    }

    /** Returns a validator built from this snapshot, or null when no ontology is bound. */
    public OntologyConformanceValidator validator() {
        if (ontology.entityTypes().isEmpty() && ontology.relationTypes().isEmpty()) {
            return null;
        }
        return new OntologyConformanceValidator(
            ontology,
            ignored -> {},
            dropOnUnknownKeyword,
            overlay.mode()
        );
    }

    /** Returns a content fingerprint for the snapshot. */
    public String fingerprint() {
        return OntologyJsonCodec.contextFingerprint(this);
    }
}
