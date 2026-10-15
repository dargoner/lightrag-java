package io.github.lightrag.ontology;

import java.util.Objects;

/**
 * Directed relation pattern belonging to a relation type.
 *
 * <p>Patterns are {@code (sourceLabel, targetLabel)} pairs. The conformance
 * validator tolerates both directions when matching an extracted relation
 * against the ontology.
 */
public record OntologyPattern(
    String sourceLabel,
    String targetLabel
) {
    public OntologyPattern {
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        Objects.requireNonNull(targetLabel, "targetLabel");
        sourceLabel = sourceLabel.strip();
        targetLabel = targetLabel.strip();
        if (sourceLabel.isEmpty() || targetLabel.isEmpty()) {
            throw new IllegalArgumentException("sourceLabel and targetLabel must not be blank");
        }
    }

    public String identityId() {
        return Identity.identityId(sourceLabel) + "\u0000" + Identity.identityId(targetLabel);
    }

    /** Returns a canonical copy whose labels are normalized identity keys. */
    public OntologyPattern toCanonical() {
        return new OntologyPattern(Identity.identityId(sourceLabel), Identity.identityId(targetLabel));
    }
}
