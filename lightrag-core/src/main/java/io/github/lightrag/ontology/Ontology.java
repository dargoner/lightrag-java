package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Storage-agnostic ontology snapshot.
 *
 * <p>All indexing, merging, diffing, pattern matching, and codec canonicalization
 * use {@link Identity#identityId(String)}, not raw labels.
 */
public record Ontology(
    List<OntologyEntityType> entityTypes,
    List<OntologyRelationType> relationTypes,
    String version,
    Map<String, String> metadata
) {
    public Ontology {
        entityTypes = entityTypes == null ? List.of() : List.copyOf(entityTypes);
        relationTypes = relationTypes == null ? List.of() : List.copyOf(relationTypes);
        version = version == null ? null : version.strip();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        requireUniqueIdentities(entityTypes, "entity");
        requireUniqueIdentities(relationTypes, "relation");
    }

    private static <T extends HasIdentity> void requireUniqueIdentities(List<T> items, String kind) {
        var seen = new java.util.HashSet<String>();
        for (T item : items) {
            if (!seen.add(item.identityId())) {
                throw new IllegalArgumentException(
                    "Duplicate " + kind + " identity: " + item.identityId()
                );
            }
        }
    }

    /**
     * Merges another ontology into this one, producing a new ontology draft.
     *
     * <p>Entity and relation types are unioned by identity. Properties and
     * patterns within matching types are unioned by identity. Descriptions are
     * kept from the non-null, longer side when both are present.
     */
    public Ontology merge(Ontology other) {
        Objects.requireNonNull(other, "other");

        Map<String, OntologyEntityType> mergedEntities = new LinkedHashMap<>();
        for (OntologyEntityType et : this.entityTypes) {
            mergedEntities.put(et.identityId(), et);
        }
        for (OntologyEntityType et : other.entityTypes) {
            mergedEntities.merge(et.identityId(), et, this::mergeEntityTypes);
        }

        Map<String, OntologyRelationType> mergedRelations = new LinkedHashMap<>();
        for (OntologyRelationType rt : this.relationTypes) {
            mergedRelations.put(rt.identityId(), rt);
        }
        for (OntologyRelationType rt : other.relationTypes) {
            mergedRelations.merge(rt.identityId(), rt, this::mergeRelationTypes);
        }

        Map<String, String> mergedMetadata = new LinkedHashMap<>(this.metadata);
        mergedMetadata.putAll(other.metadata);

        return new Ontology(
            List.copyOf(mergedEntities.values()),
            List.copyOf(mergedRelations.values()),
            null,
            Map.copyOf(mergedMetadata)
        );
    }

    private OntologyEntityType mergeEntityTypes(OntologyEntityType a, OntologyEntityType b) {
        return new OntologyEntityType(
            longerLabel(a.label(), b.label()),
            longerDescription(a.description(), b.description()),
            mergeProperties(a.properties(), b.properties()),
            a.sourceBinding() != null ? a.sourceBinding() : b.sourceBinding()
        );
    }

    private OntologyRelationType mergeRelationTypes(OntologyRelationType a, OntologyRelationType b) {
        return new OntologyRelationType(
            longerLabel(a.label(), b.label()),
            longerDescription(a.description(), b.description()),
            mergePatterns(a.patterns(), b.patterns()),
            mergeProperties(a.properties(), b.properties()),
            a.sourceBinding() != null ? a.sourceBinding() : b.sourceBinding()
        );
    }

    private List<OntologyProperty> mergeProperties(List<OntologyProperty> a, List<OntologyProperty> b) {
        Map<String, OntologyProperty> map = new LinkedHashMap<>();
        for (OntologyProperty p : a) {
            map.put(p.identityId(), p);
        }
        for (OntologyProperty p : b) {
            map.merge(p.identityId(), p, (x, y) -> {
                if (x.type() != y.type()) {
                    throw new OntologyContradictionException(
                        "Property '" + x.identityId() + "' has conflicting types: "
                            + x.type() + " and " + y.type()
                    );
                }
                return new OntologyProperty(
                    longerLabel(x.name(), y.name()),
                    x.type(),
                    longerDescription(x.description(), y.description()),
                    x.sourceBinding() != null ? x.sourceBinding() : y.sourceBinding()
                );
            });
        }
        return List.copyOf(map.values());
    }

    private List<OntologyPattern> mergePatterns(List<OntologyPattern> a, List<OntologyPattern> b) {
        Map<String, OntologyPattern> map = new LinkedHashMap<>();
        for (OntologyPattern p : a) {
            map.put(p.identityId(), p);
        }
        for (OntologyPattern p : b) {
            map.putIfAbsent(p.identityId(), p);
        }
        return List.copyOf(map.values());
    }

    private static String longerLabel(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a.length() >= b.length() ? a : b;
    }

    private static String longerDescription(String a, String b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a.length() >= b.length() ? a : b;
    }

    /**
     * Returns a canonical copy whose labels and names are normalized identity keys,
     * suitable for hashing and cross-repository comparison.
     */
    public Ontology toCanonical() {
        return new Ontology(
            entityTypes.stream().map(OntologyEntityType::toCanonical).toList(),
            relationTypes.stream().map(OntologyRelationType::toCanonical).toList(),
            version,
            metadata
        );
    }
}
