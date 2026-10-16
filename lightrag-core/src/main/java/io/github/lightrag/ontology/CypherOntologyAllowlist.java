package io.github.lightrag.ontology;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Business-level allowlist for ontology-constrained Cypher validation.
 *
 * <p>Assembled by the host from the applied ontology snapshot: entity type
 * labels plus relation keyword labels of the bound ontology, always including
 * the extraction fallback type {@code Other}. Values are stored in
 * {@link Identity#identityId} form, and {@link CypherOntologyChecker}
 * normalizes the values it extracts from a query the same way, so the
 * comparison is case/whitespace insensitive.
 *
 * @param typeValues       allowed business entity type values (identity form, includes {@code other})
 * @param relationKeywords allowed business relation keywords (identity form)
 */
public record CypherOntologyAllowlist(
    Set<String> typeValues,
    Set<String> relationKeywords
) {

    /** Fallback entity type kept by extraction; always allowed. */
    public static final String FALLBACK_TYPE = "Other";

    public CypherOntologyAllowlist {
        LinkedHashSet<String> normalizedTypes = normalize(typeValues);
        normalizedTypes.add(Identity.identityId(FALLBACK_TYPE));
        typeValues = Set.copyOf(normalizedTypes);
        relationKeywords = Set.copyOf(normalize(relationKeywords));
    }

    /**
     * Builds an allowlist from raw business labels.
     *
     * @param entityTypes     entity type labels of the applied ontology; may be null
     * @param relationKeywords relation keyword labels of the applied ontology; may be null
     */
    public static CypherOntologyAllowlist of(
        Collection<String> entityTypes, Collection<String> relationKeywords) {
        return new CypherOntologyAllowlist(normalize(entityTypes), normalize(relationKeywords));
    }

    /** Returns an allowlist whose only allowed type value is the fallback {@code Other}. */
    public static CypherOntologyAllowlist empty() {
        return new CypherOntologyAllowlist(Set.of(), Set.of());
    }

    /** Whether the raw value (as written in a query) is an allowed business entity type. */
    public boolean containsTypeValue(String raw) {
        String identity = identityOrNull(raw);
        return identity != null && typeValues.contains(identity);
    }

    /** Whether the raw value (as written in a query) is a declared business relation keyword. */
    public boolean containsRelationKeyword(String raw) {
        String identity = identityOrNull(raw);
        return identity != null && relationKeywords.contains(identity);
    }

    private static LinkedHashSet<String> normalize(Collection<String> labels) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (labels != null) {
            for (String label : labels) {
                String identity = identityOrNull(label);
                if (identity != null) {
                    normalized.add(identity);
                }
            }
        }
        return normalized;
    }

    private static String identityOrNull(String label) {
        if (label == null) {
            return null;
        }
        try {
            return Identity.identityId(label);
        } catch (IllegalArgumentException blank) {
            return null;
        }
    }
}
