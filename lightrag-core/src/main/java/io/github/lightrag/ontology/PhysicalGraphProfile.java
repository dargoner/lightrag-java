package io.github.lightrag.ontology;

import java.util.Objects;
import java.util.Set;

/**
 * Physical descriptor of the configured graph backend.
 *
 * <p>The host passes the labels and type-property name that its graph adapter
 * actually writes (for example {@code Entity}/{@code RELATION}/{@code type}
 * for Neo4j, {@code base}/{@code DIRECTED}/{@code entity_type} for FalkorDB).
 * The SDK deliberately bundles no backend mapping table so this descriptor
 * cannot drift from adapter facts; every physical check in
 * {@link CypherOntologyChecker} treats it as the single source of truth.
 *
 * @param nodeLabels        physical node labels (for example Entity / base)
 * @param relationshipTypes physical relationship types (for example RELATION / DIRECTED)
 * @param typePropertyName  property carrying the business entity type (for example type / entity_type)
 */
public record PhysicalGraphProfile(
    Set<String> nodeLabels,
    Set<String> relationshipTypes,
    String typePropertyName
) {

    public PhysicalGraphProfile {
        nodeLabels = nodeLabels == null ? Set.of() : Set.copyOf(nodeLabels);
        relationshipTypes = relationshipTypes == null ? Set.of() : Set.copyOf(relationshipTypes);
        Objects.requireNonNull(typePropertyName, "typePropertyName");
        typePropertyName = typePropertyName.strip();
        if (typePropertyName.isEmpty()) {
            throw new IllegalArgumentException("typePropertyName must not be blank");
        }
    }
}
