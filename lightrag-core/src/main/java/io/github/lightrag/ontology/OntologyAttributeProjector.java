package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Final, pure projection of materialized attributes onto the declarations of
 * the final entity type or relation keyword set.
 *
 * <p>Both entry points derive their schema from {@link Ontology#toCanonical()}
 * on every call. Values are re-coerced against the final declaration, keys that
 * the final owner does not declare are dropped, duplicate identities with
 * differing canonical values are dropped, and the semantic caps are applied.
 * Input and output keys are identities. The projector reports nothing: final
 * projection is a deterministic convergence step, not a second validation
 * pass, so the same problem is never counted twice.
 */
public final class OntologyAttributeProjector {

    private OntologyAttributeProjector() {}

    /**
     * Projects attributes onto the declarations of the final entity type.
     *
     * @param type       the final entity type label; may be {@code null}
     * @param attributes the current identity-keyed attributes; may be {@code null}
     * @param ontology   the ontology used for extraction; {@code null} is a no-op
     *                   and passes the attributes through unchanged, matching the
     *                   optional-projector contract
     * @return the retained, re-coerced attributes sorted by identity
     */
    public static Map<String, Object> projectEntity(
        String type,
        Map<String, Object> attributes,
        Ontology ontology
    ) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        if (ontology == null) {
            return Map.copyOf(attributes);
        }
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(ontology);
        List<OntologyProperty> declared = schema.entityProperties(type);
        if (declared.isEmpty()) {
            return Map.of();
        }
        Map<String, List<OntologyPropertyType>> declarations = new TreeMap<>();
        for (OntologyProperty property : declared) {
            declarations
                .computeIfAbsent(property.identityId(), ignored -> new ArrayList<>())
                .add(property.type());
        }
        return project(attributes, declarations);
    }

    /**
     * Projects attributes onto the declarations of every relation type hit by
     * the final keyword list.
     *
     * @param keywords   the final comma-separated relation keywords; may be
     *                   {@code null}
     * @param attributes the current identity-keyed attributes; may be {@code null}
     * @param ontology   the ontology used for extraction; {@code null} is a no-op
     *                   and passes the attributes through unchanged, matching the
     *                   optional-projector contract
     * @return the retained, re-coerced attributes sorted by identity
     */
    public static Map<String, Object> projectRelation(
        String keywords,
        Map<String, Object> attributes,
        Ontology ontology
    ) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        if (ontology == null) {
            return Map.copyOf(attributes);
        }
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(ontology);
        Map<String, List<OntologyPropertyType>> declarations = new TreeMap<>();
        for (String relationType : schema.relationTypesForKeywords(keywords)) {
            for (OntologyProperty property : schema.relationProperties(relationType)) {
                declarations
                    .computeIfAbsent(property.identityId(), ignored -> new ArrayList<>())
                    .add(property.type());
            }
        }
        if (declarations.isEmpty()) {
            return Map.of();
        }
        return project(attributes, declarations);
    }

    private static Map<String, Object> project(
        Map<String, Object> attributes,
        Map<String, List<OntologyPropertyType>> declarations
    ) {
        Map<String, List<Object>> groups = new TreeMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            String identity = OntologyAttributeSchema.identityOrNull(entry.getKey());
            if (identity == null) {
                continue;
            }
            groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(entry.getValue());
        }

        Map<String, Object> values = new TreeMap<>();
        for (Map.Entry<String, List<Object>> entry : groups.entrySet()) {
            String identity = entry.getKey();
            List<OntologyPropertyType> declaredTypes = declarations.get(identity);
            if (declaredTypes == null || declaredTypes.isEmpty()) {
                continue;
            }
            Set<OntologyPropertyType> distinctTypes = EnumSet.noneOf(OntologyPropertyType.class);
            distinctTypes.addAll(declaredTypes);
            if (distinctTypes.size() > 1) {
                continue;
            }
            OntologyPropertyType type = distinctTypes.iterator().next();

            List<Object> coerced = new ArrayList<>(entry.getValue().size());
            boolean failed = false;
            for (Object rawValue : entry.getValue()) {
                try {
                    coerced.add(AttributeValues.coerce(rawValue, type));
                } catch (IllegalArgumentException ex) {
                    failed = true;
                    break;
                }
            }
            if (failed) {
                continue;
            }
            Set<String> canonicalValues = new LinkedHashSet<>();
            for (Object value : coerced) {
                canonicalValues.add(AttributeJsonCodec.canonicalValueJson(value));
            }
            if (canonicalValues.size() > 1) {
                continue;
            }
            values.put(identity, coerced.get(0));
        }
        return AttributeValues.applyCaps(values, declarations.keySet());
    }
}
