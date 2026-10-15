package io.github.lightrag.ontology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validates the structural integrity of an ontology snapshot.
 *
 * <p>Errors are structural violations that make the snapshot unusable: blank
 * labels, duplicate identities, kernel or fallback reserved labels, duplicate
 * property identities, duplicate patterns, and multiple labels bound to the
 * same normalized source table or column signature. Warnings are advisory
 * diagnostics: relation patterns that reference undeclared entity types and
 * entity types that no declared pattern references.
 *
 * <p>Every identity comparison goes through {@link Identity#identityId(String)};
 * table and column signatures are normalized (trimmed, lower-cased, unquoted)
 * before comparison.
 */
public final class OntologyStructureValidator {

    private static final Set<String> NO_STANDALONE_LABELS = Set.of();

    private OntologyStructureValidator() {}

    /** Validation knobs; the default only reports warnings for unconnected labels. */
    public record Options(boolean strictUnconnected, Set<String> standaloneLabels) {
        public Options {
            standaloneLabels = standaloneLabels == null
                ? NO_STANDALONE_LABELS
                : standaloneLabels.stream()
                    .map(Identity::identityId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        public static Options defaults() {
            return new Options(false, NO_STANDALONE_LABELS);
        }
    }

    /** Validation outcome: the validated ontology plus advisory diagnostics. */
    public record Result(Ontology ontology, List<String> warnings) {
        public Result {
            Objects.requireNonNull(ontology, "ontology");
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /**
     * Validates the ontology and returns it unchanged.
     *
     * @param ontology the ontology to validate
     * @return the same ontology
     * @throws IllegalArgumentException if the ontology is structurally invalid
     */
    public static Ontology validate(Ontology ontology) {
        return validate(ontology, Options.defaults()).ontology();
    }

    /**
     * Validates the ontology and returns the advisory diagnostics.
     *
     * @param ontology the ontology to validate
     * @param options  validation options
     * @return the validated ontology and its warnings
     * @throws IllegalArgumentException if the ontology is structurally invalid
     */
    public static Result validate(Ontology ontology, Options options) {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(options, "options");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Set<String> entityIds = new LinkedHashSet<>();
        for (OntologyEntityType entity : ontology.entityTypes()) {
            String id = entity.identityId();
            if (!entityIds.add(id)) {
                errors.add("Duplicate entity type identity: " + id);
                continue;
            }
            checkReserved("entity", id, errors);
            validateProperties("entity", id, entity.properties(), errors);
        }

        Set<String> relationIds = new LinkedHashSet<>();
        for (OntologyRelationType relation : ontology.relationTypes()) {
            String id = relation.identityId();
            if (!relationIds.add(id)) {
                errors.add("Duplicate relation type identity: " + id);
                continue;
            }
            checkReserved("relation", id, errors);
            validateProperties("relation", id, relation.properties(), errors);
            validatePatterns(id, relation, errors);
        }

        validateSourceSignatures(ontology, errors);
        collectPatternDiagnostics(ontology, entityIds, options, warnings, errors);

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        return new Result(ontology, List.copyOf(warnings));
    }

    private static void checkReserved(String kind, String identityId, List<String> errors) {
        if (ReservedLabels.isKernelReserved(identityId)) {
            errors.add(
                "Reserved " + kind + " label conflicts with a kernel structural concept: " + identityId
            );
        } else if (ReservedLabels.isExtractionFallback(identityId)) {
            errors.add(
                "Reserved " + kind + " label conflicts with the extraction fallback: " + identityId
            );
        }
    }

    private static void validateProperties(
        String ownerKind,
        String ownerId,
        List<OntologyProperty> properties,
        List<String> errors
    ) {
        Set<String> seen = new HashSet<>();
        for (OntologyProperty property : properties) {
            String id = property.identityId();
            if (!seen.add(id)) {
                errors.add("Duplicate property identity '" + id + "' on " + ownerKind + " '" + ownerId + "'");
            }
        }
    }

    private static void validatePatterns(
        String relationId,
        OntologyRelationType relation,
        List<String> errors
    ) {
        Set<String> seen = new HashSet<>();
        for (OntologyPattern pattern : relation.patterns()) {
            if (!seen.add(pattern.identityId())) {
                errors.add(
                    "Relation '" + relationId + "' contains duplicate pattern: "
                        + pattern.sourceLabel() + " -> " + pattern.targetLabel()
                );
            }
        }
    }

    private static void validateSourceSignatures(Ontology ontology, List<String> errors) {
        Map<String, String> ownersBySignature = new LinkedHashMap<>();

        for (OntologyEntityType entity : ontology.entityTypes()) {
            Signature signature = entityTableSignature(entity.sourceBinding());
            if (signature != null) {
                claimSignature(ownersBySignature, signature, "entity", entity.identityId(), errors);
            }
            for (OntologyProperty property : entity.properties()) {
                Signature propertySignature = propertySignature(property.sourceBinding());
                if (propertySignature != null) {
                    claimSignature(
                        ownersBySignature,
                        propertySignature,
                        "property",
                        "entity '" + entity.identityId() + "' property '" + property.identityId() + "'",
                        errors
                    );
                }
            }
        }

        for (OntologyRelationType relation : ontology.relationTypes()) {
            Signature signature = relationSignature(relation.sourceBinding());
            if (signature != null) {
                claimSignature(ownersBySignature, signature, "relation", relation.identityId(), errors);
            }
            for (OntologyProperty property : relation.properties()) {
                Signature propertySignature = propertySignature(property.sourceBinding());
                if (propertySignature != null) {
                    claimSignature(
                        ownersBySignature,
                        propertySignature,
                        "property",
                        "relation '" + relation.identityId() + "' property '" + property.identityId() + "'",
                        errors
                    );
                }
            }
        }
    }

    private static void claimSignature(
        Map<String, String> ownersBySignature,
        Signature signature,
        String kind,
        String owner,
        List<String> errors
    ) {
        String previous = ownersBySignature.putIfAbsent(signature.key(), kind + " '" + owner + "'");
        if (previous != null) {
            errors.add("Source signature '" + signature.display() + "' is bound by multiple ontology labels: "
                + previous + " and " + kind + " '" + owner + "'");
        }
    }

    private static void collectPatternDiagnostics(
        Ontology ontology,
        Set<String> entityIds,
        Options options,
        List<String> warnings,
        List<String> errors
    ) {
        Set<String> referencedEntityIds = new LinkedHashSet<>();
        for (OntologyRelationType relation : ontology.relationTypes()) {
            for (OntologyPattern pattern : relation.patterns()) {
                String sourceId = Identity.identityId(pattern.sourceLabel());
                String targetId = Identity.identityId(pattern.targetLabel());
                referencedEntityIds.add(sourceId);
                referencedEntityIds.add(targetId);
                warnUndeclared(relation, pattern, "source", pattern.sourceLabel(), sourceId, entityIds, warnings);
                warnUndeclared(relation, pattern, "target", pattern.targetLabel(), targetId, entityIds, warnings);
            }
        }

        for (OntologyEntityType entity : ontology.entityTypes()) {
            if (referencedEntityIds.contains(entity.identityId())
                || options.standaloneLabels().contains(entity.identityId())) {
                continue;
            }
            String message = "Entity type '" + entity.identityId() + "' is not referenced by any relation pattern";
            if (options.strictUnconnected()) {
                errors.add(message);
            } else {
                warnings.add(message);
            }
        }
    }

    private static void warnUndeclared(
        OntologyRelationType relation,
        OntologyPattern pattern,
        String endpoint,
        String displayLabel,
        String identityId,
        Set<String> entityIds,
        List<String> warnings
    ) {
        if (entityIds.contains(identityId)) {
            return;
        }
        warnings.add(
            "Relation '" + relation.identityId() + "' pattern " + pattern.sourceLabel() + " -> "
                + pattern.targetLabel() + " references undeclared entity type '" + displayLabel
                + "' as " + endpoint
        );
    }

    /** Source signature: the comparison key (component-boundary safe) plus its display form. */
    private record Signature(String key, String display) {}

    private static Signature entityTableSignature(SourceBinding binding) {
        if (binding == null || binding.tableName() == null) {
            return null;
        }
        List<String> table = normalizeIdentityComponents(binding.tableName());
        return new Signature("table:" + encodeComponents(table), "table:" + String.join(".", table));
    }

    private static Signature propertySignature(SourceBinding binding) {
        if (binding == null || binding.tableName() == null || binding.column() == null) {
            return null;
        }
        List<String> table = normalizeIdentityComponents(binding.tableName());
        List<String> column = normalizeIdentityComponents(binding.column());
        return new Signature(
            "column:" + encodeComponents(table) + "|" + encodeComponents(column),
            "column:" + String.join(".", table) + "." + String.join(".", column)
        );
    }

    private static Signature relationSignature(SourceBinding binding) {
        if (binding == null
            || binding.tableName() == null
            || binding.column() == null
            || binding.refTableName() == null
            || binding.refColumn() == null) {
            return null;
        }
        List<String> table = normalizeIdentityComponents(binding.tableName());
        List<String> column = normalizeIdentityComponents(binding.column());
        List<String> refTable = normalizeIdentityComponents(binding.refTableName());
        List<String> refColumn = normalizeIdentityComponents(binding.refColumn());
        return new Signature(
            "fk:" + encodeComponents(table) + "|" + encodeComponents(column)
                + ">" + encodeComponents(refTable) + "|" + encodeComponents(refColumn),
            "fk:" + String.join(".", table) + "." + String.join(".", column)
                + "->" + String.join(".", refTable) + "." + String.join(".", refColumn)
        );
    }

    /**
     * Encodes components so that boundaries survive serialization into a single
     * comparison key: each component is length-prefixed, making the encoding
     * injective. {@code ["a.b", "c"]} and {@code ["a", "b.c"]} therefore stay
     * distinct even though their dot-joined display forms coincide.
     */
    private static String encodeComponents(List<String> components) {
        StringBuilder builder = new StringBuilder();
        for (String component : components) {
            builder.append(component.length()).append(':').append(component);
        }
        return builder.toString();
    }

    /**
     * Normalizes a table or column identity: trims, strips wrapping quotes from
     * each dot-separated component (respecting quoted components that contain
     * dots), then lower-cases. {@code public.users} and {@code "PUBLIC"."USERS"}
     * normalize to the same identity.
     */
    static String normalizeIdentity(String value) {
        return String.join(".", normalizeIdentityComponents(value));
    }

    /**
     * Splits a table or column identity into its normalized components: quoted
     * spans (with {@code "}, {@code `} or {@code [...]} quotes) are kept as one
     * component even when they contain dots, and each component is stripped of
     * wrapping quotes and lower-cased.
     */
    static List<String> normalizeIdentityComponents(String value) {
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return List.of(stripped.toLowerCase(Locale.ROOT));
        }
        List<String> components = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char closingQuote = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (closingQuote != 0) {
                current.append(c);
                if (c == closingQuote) {
                    closingQuote = 0;
                }
            } else if (c == '"' || c == '`') {
                closingQuote = c;
                current.append(c);
            } else if (c == '[') {
                closingQuote = ']';
                current.append(c);
            } else if (c == '.') {
                components.add(stripWrappingQuotes(current.toString()).toLowerCase(Locale.ROOT));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        components.add(stripWrappingQuotes(current.toString()).toLowerCase(Locale.ROOT));
        return components;
    }

    private static String stripWrappingQuotes(String value) {
        String result = value.strip();
        boolean changed = true;
        while (changed && result.length() >= 2) {
            changed = false;
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"')
                || (first == '`' && last == '`')
                || (first == '[' && last == ']')) {
                result = result.substring(1, result.length() - 1).strip();
                changed = true;
            }
        }
        return result;
    }
}
