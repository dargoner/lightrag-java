package io.github.lightrag.ontology;

import java.util.List;
import java.util.Objects;

/**
 * Property declaration for an ontology entity or relation type.
 */
public record OntologyProperty(
    String name,
    OntologyPropertyType type,
    String description,
    SourceBinding sourceBinding
) {
    public OntologyProperty {
        Objects.requireNonNull(name, "name");
        type = type == null ? OntologyPropertyType.STRING : type;
        name = name.strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? null : description.strip();
    }

    public String identityId() {
        return Identity.identityId(name);
    }

    /** Returns a canonical copy whose {@code name} is the normalized identity key. */
    public OntologyProperty toCanonical() {
        return new OntologyProperty(identityId(), type, description, sourceBinding);
    }
}
