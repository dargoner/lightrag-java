package io.github.lightrag.ontology;

import java.util.List;
import java.util.Objects;

/**
 * Entity type declaration in an ontology.
 */
public record OntologyEntityType(
    String label,
    String description,
    List<OntologyProperty> properties,
    SourceBinding sourceBinding
) implements HasIdentity {
    public OntologyEntityType {
        Objects.requireNonNull(label, "label");
        label = label.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        description = description == null ? null : description.strip();
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    @Override
    public String identityId() {
        return Identity.identityId(label);
    }

    /** Returns a canonical copy whose {@code label} and property names are normalized identity keys. */
    public OntologyEntityType toCanonical() {
        return new OntologyEntityType(
            identityId(),
            description,
            properties.stream().map(OntologyProperty::toCanonical).toList(),
            sourceBinding
        );
    }
}
