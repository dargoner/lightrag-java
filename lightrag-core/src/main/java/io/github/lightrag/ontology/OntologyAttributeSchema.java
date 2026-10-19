package io.github.lightrag.ontology;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Typed attribute schema derived deterministically from a canonical ontology.
 *
 * <p>All owner keys, property identities, and relation keywords are normalized
 * with {@link Identity#identityId(String)}. Entity and relation indexes hold
 * their declarations sorted by property identity, so the same ontology always
 * yields the same schema regardless of declaration order.
 *
 * <p>{@link #from(Ontology)} first calls {@link Ontology#toCanonical()}, which
 * makes the schema identical for an in-memory ontology that still carries
 * display-name spellings and for the same ontology restored from a persisted
 * canonical task context.
 *
 * @param entityTypes     entity type identity to declared properties
 * @param relationTypes   relation type identity to declared properties
 * @param relationKeywords relation keyword identity to the relation type
 *                         identities it resolves to
 */
public record OntologyAttributeSchema(
    Map<String, List<OntologyProperty>> entityTypes,
    Map<String, List<OntologyProperty>> relationTypes,
    Map<String, Set<String>> relationKeywords
) {

    /**
     * Kernel fields already carried by every entity. Declaring them is accepted
     * for compatibility, but they are never rendered, bound, or materialized.
     * Keys are stored separator-free so {@code Relationship Description},
     * {@code relationship_description}, and {@code relationshipDescription} all
     * match.
     */
    private static final Set<String> RESERVED_ENTITY_PROPERTIES = Set.of(
        "name",
        "type",
        "description",
        "aliases"
    );

    /**
     * Kernel fields already carried by every relation. Declaring them is
     * accepted for compatibility, but they are never rendered, bound, or
     * materialized. Keys are stored separator-free so every spelling of a
     * multi-word kernel field matches.
     */
    private static final Set<String> RESERVED_RELATION_PROPERTIES = Set.of(
        "sourceentity",
        "targetentity",
        "relationshipkeywords",
        "relationshipdescription",
        "weight"
    );

    public OntologyAttributeSchema {
        entityTypes = normalizePropertyIndex(entityTypes, RESERVED_ENTITY_PROPERTIES);
        relationTypes = normalizePropertyIndex(relationTypes, RESERVED_RELATION_PROPERTIES);
        relationKeywords = normalizeKeywordIndex(relationKeywords);
    }

    /**
     * Derives the schema from an ontology's canonical form.
     *
     * @param ontology the ontology; must not be {@code null}
     * @return the typed schema
     */
    public static OntologyAttributeSchema from(Ontology ontology) {
        Objects.requireNonNull(ontology, "ontology");
        Ontology canonical = ontology.toCanonical();

        Map<String, List<OntologyProperty>> entities = new TreeMap<>();
        for (OntologyEntityType entityType : canonical.entityTypes()) {
            entities.put(entityType.identityId(), entityType.properties());
        }
        Map<String, List<OntologyProperty>> relations = new TreeMap<>();
        Map<String, Set<String>> keywords = new TreeMap<>();
        for (OntologyRelationType relationType : canonical.relationTypes()) {
            String identity = relationType.identityId();
            relations.put(identity, relationType.properties());
            keywords.put(identity, Set.of(identity));
        }
        return new OntologyAttributeSchema(entities, relations, keywords);
    }

    /** Returns an empty schema that materializes no attributes. */
    public static OntologyAttributeSchema empty() {
        return new OntologyAttributeSchema(Map.of(), Map.of(), Map.of());
    }

    /**
     * Returns the declared properties of an entity type.
     *
     * @param type the entity type label or identity
     * @return the declarations sorted by property identity; empty when the type
     *         is blank or not declared
     */
    public List<OntologyProperty> entityProperties(String type) {
        String identity = identityOrNull(type);
        return identity == null ? List.of() : entityTypes.getOrDefault(identity, List.of());
    }

    /**
     * Returns the declared properties of a relation type.
     *
     * @param relationType the relation type label or identity
     * @return the declarations sorted by property identity; empty when the type
     *         is blank or not declared
     */
    public List<OntologyProperty> relationProperties(String relationType) {
        String identity = identityOrNull(relationType);
        return identity == null ? List.of() : relationTypes.getOrDefault(identity, List.of());
    }

    /**
     * Resolves a raw relation keyword list to the relation types it hits.
     *
     * <p>Keywords are split on commas, trimmed, blank parts are ignored, and
     * every part is matched by identity. The result is de-duplicated by identity
     * and sorted.
     *
     * @param keywords the comma-separated keywords; may be {@code null}
     * @return the matched relation type identities, sorted; empty when nothing
     *         matches
     */
    public List<String> relationTypesForKeywords(String keywords) {
        if (keywords == null) {
            return List.of();
        }
        Set<String> hits = new TreeSet<>();
        for (String part : keywords.split(",")) {
            String identity = identityOrNull(part);
            if (identity == null) {
                continue;
            }
            Set<String> owners = relationKeywords.get(identity);
            if (owners != null) {
                hits.addAll(owners);
            }
        }
        return List.copyOf(hits);
    }

    /** Returns {@code true} when no entity, relation, or keyword is declared. */
    public boolean isEmpty() {
        return entityTypes.isEmpty() && relationTypes.isEmpty() && relationKeywords.isEmpty();
    }

    /**
     * Whitespace-safe identity helper: returns {@code null} for {@code null} or
     * blank input instead of letting {@link Identity#identityId(String)} throw.
     */
    static String identityOrNull(String label) {
        if (label == null) {
            return null;
        }
        String normalized = Identity.normalizeWhitespace(label);
        return normalized == null ? null : Identity.identityId(normalized);
    }

    private static Map<String, List<OntologyProperty>> normalizePropertyIndex(
        Map<String, List<OntologyProperty>> index,
        Set<String> reservedProperties
    ) {
        if (index == null || index.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, OntologyProperty>> collected = new TreeMap<>();
        for (Map.Entry<String, List<OntologyProperty>> entry : index.entrySet()) {
            String owner = Identity.identityId(Objects.requireNonNull(entry.getKey(), "owner"));
            Map<String, OntologyProperty> properties =
                collected.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
            if (entry.getValue() == null) {
                continue;
            }
            for (OntologyProperty property : entry.getValue()) {
                if (property == null) {
                    continue;
                }
                String identity = property.identityId();
                if (reservedProperties.contains(Identity.separatorFree(identity))) {
                    continue;
                }
                properties.putIfAbsent(identity, property);
            }
        }
        Map<String, List<OntologyProperty>> normalized = new TreeMap<>();
        collected.forEach((owner, properties) -> normalized.put(
            owner,
            List.copyOf(new TreeMap<>(properties).values())
        ));
        return Collections.unmodifiableMap(normalized);
    }

    private static Map<String, Set<String>> normalizeKeywordIndex(
        Map<String, Set<String>> index
    ) {
        if (index == null || index.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> collected = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : index.entrySet()) {
            String keyword = Identity.identityId(Objects.requireNonNull(entry.getKey(), "keyword"));
            Set<String> owners = collected.computeIfAbsent(keyword, ignored -> new TreeSet<>());
            if (entry.getValue() == null) {
                continue;
            }
            for (String owner : entry.getValue()) {
                if (owner != null) {
                    owners.add(Identity.identityId(owner));
                }
            }
        }
        Map<String, Set<String>> normalized = new TreeMap<>();
        collected.forEach((keyword, owners) -> normalized.put(
            keyword,
            Collections.unmodifiableSet(new TreeSet<>(owners))
        ));
        return Collections.unmodifiableMap(normalized);
    }
}
