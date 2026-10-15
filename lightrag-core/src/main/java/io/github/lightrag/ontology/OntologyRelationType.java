package io.github.lightrag.ontology;

import java.util.List;
import java.util.Objects;

/**
 * Relation type declaration in an ontology.
 */
public record OntologyRelationType(
    String label,
    String description,
    List<OntologyPattern> patterns,
    List<OntologyProperty> properties,
    SourceBinding sourceBinding
) implements HasIdentity {
    public OntologyRelationType {
        Objects.requireNonNull(label, "label");
        label = label.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        description = description == null ? null : description.strip();
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    @Override
    public String identityId() {
        return Identity.identityId(label);
    }

    /** Returns a canonical copy whose {@code label}, pattern labels, and property names are normalized identity keys. */
    public OntologyRelationType toCanonical() {
        return new OntologyRelationType(
            identityId(),
            description,
            patterns.stream().map(OntologyPattern::toCanonical).toList(),
            properties.stream().map(OntologyProperty::toCanonical).toList(),
            sourceBinding
        );
    }
}
