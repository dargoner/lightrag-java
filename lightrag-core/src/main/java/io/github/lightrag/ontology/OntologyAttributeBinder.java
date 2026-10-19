package io.github.lightrag.ontology;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The single binding entry point for extracted attributes.
 *
 * <p>The binder runs before any record is constructed: it structurally
 * sanitizes the raw JSON, resolves every candidate against the typed schema
 * derived from the canonical ontology, coerces values, rules on duplicate
 * identities <em>after</em> coercion, applies the semantic caps, and returns an
 * identity-keyed value map plus diagnostics. Attribute problems never throw and
 * never drop the enclosing entity or relation; they only drop keys.
 *
 * <p>Owner resolution:
 * <ul>
 *   <li>entity: the entity type is the owner; an unknown type has no
 *       declarations, so all attributes are dropped as undeclared;</li>
 *   <li>relation: every relation type hit by the keyword list is an owner. A
 *       property declared by one or more hits with the same type is bound; a
 *       property declared with different types across hits is
 *       {@link UnmatchedLabel.Reason#ATTRIBUTE_TYPE_CONFLICT}; a property that
 *       no hit declares is undeclared; a keyword list that hits no relation
 *       type drops all attributes.</li>
 * </ul>
 *
 * <p>Groups and output are ordered by {@code (identity, rawKey)}; diagnostics
 * are ordered by owner, raw label, and reason using the whitespace-safe
 * {@link #diagnosticSortKey(String)}. The {@link UnmatchedLabel} fields keep
 * the original spelling (including an empty raw label for whole-object limit
 * issues); the sentinel is only used for ordering.
 */
public final class OntologyAttributeBinder {

    /** Sentinel used for blank owner or raw labels when ordering diagnostics. */
    public static final String EMPTY_SORT_KEY = "<empty>";

    private static final Comparator<UnmatchedLabel> DIAGNOSTIC_ORDER = Comparator
        .comparing((UnmatchedLabel diagnostic) -> diagnosticSortKey(diagnostic.ownerLabel()))
        .thenComparing((UnmatchedLabel diagnostic) -> diagnosticSortKey(diagnostic.rawLabel()))
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.reason() == null ? "" : diagnostic.reason().name())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.kind() == null ? "" : diagnostic.kind().name())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.rawLabel() == null ? "" : diagnostic.rawLabel())
        .thenComparing((UnmatchedLabel diagnostic) ->
            diagnostic.chunkId() == null ? "" : diagnostic.chunkId());

    /**
     * Returns the whitespace-safe ordering key for an owner or raw label.
     *
     * <p>Blank input maps to {@value #EMPTY_SORT_KEY}; non-blank input maps to
     * {@link Identity#identityId(String)}. This method never throws for blank
     * labels, which matters because whole-object limit issues intentionally use
     * an empty raw label.
     *
     * @param label the raw label; may be {@code null}
     * @return the ordering key
     */
    public static String diagnosticSortKey(String label) {
        if (label == null) {
            return EMPTY_SORT_KEY;
        }
        String normalized = Identity.normalizeWhitespace(label);
        return normalized == null ? EMPTY_SORT_KEY : Identity.identityId(normalized);
    }

    /**
     * Binds entity attributes against the final entity type.
     *
     * @param schema         the typed schema; {@code null} binds nothing
     * @param chunkId        the owning chunk id, copied into every diagnostic
     * @param type           the entity type label
     * @param attributesNode the raw {@code attributes} JSON; may be {@code null}
     * @return the identity-keyed attributes and diagnostics
     */
    public BoundAttributes bindEntity(
        OntologyAttributeSchema schema,
        String chunkId,
        String type,
        JsonNode attributesNode
    ) {
        if (schema == null) {
            return new BoundAttributes(Map.of(), List.of());
        }
        RawAttributes raw = AttributeValues.sanitizeRaw(attributesNode);
        String ownerLabel = identityOrEmpty(type);
        List<UnmatchedLabel> diagnostics = new ArrayList<>();
        addRawIssues(raw.issues(), chunkId, ownerLabel, diagnostics);
        if (raw.candidates().isEmpty() || schema.isEmpty()) {
            return new BoundAttributes(Map.of(), sortDiagnostics(diagnostics));
        }

        List<OntologyProperty> declared = schema.entityProperties(type);
        if (declared.isEmpty()) {
            diagnostics.addAll(undeclaredDiagnostics(raw.candidates(), chunkId, ownerLabel));
            return new BoundAttributes(Map.of(), sortDiagnostics(diagnostics));
        }

        Map<String, List<Declaration>> declarations = new TreeMap<>();
        for (OntologyProperty property : declared) {
            declarations
                .computeIfAbsent(property.identityId(), ignored -> new ArrayList<>())
                .add(new Declaration(property.type(), ownerLabel));
        }
        return bindCandidates(
            raw.candidates(),
            chunkId,
            ownerLabel,
            resolveDeclarations(declarations),
            diagnostics
        );
    }

    /**
     * Binds relation attributes against every relation type hit by the keyword
     * list.
     *
     * @param schema         the typed schema; {@code null} binds nothing
     * @param chunkId        the owning chunk id, copied into every diagnostic
     * @param keywords       the comma-separated relation keywords
     * @param attributesNode the raw {@code attributes} JSON; may be {@code null}
     * @return the identity-keyed attributes and diagnostics
     */
    public BoundAttributes bindRelation(
        OntologyAttributeSchema schema,
        String chunkId,
        String keywords,
        JsonNode attributesNode
    ) {
        if (schema == null) {
            return new BoundAttributes(Map.of(), List.of());
        }
        RawAttributes raw = AttributeValues.sanitizeRaw(attributesNode);
        List<String> hitTypes = schema.relationTypesForKeywords(keywords);
        String fallbackOwner = hitTypes.isEmpty() ? "" : hitTypes.get(0);
        List<UnmatchedLabel> diagnostics = new ArrayList<>();
        addRawIssues(raw.issues(), chunkId, fallbackOwner, diagnostics);
        if (raw.candidates().isEmpty() || schema.isEmpty()) {
            return new BoundAttributes(Map.of(), sortDiagnostics(diagnostics));
        }
        if (hitTypes.isEmpty()) {
            diagnostics.addAll(undeclaredDiagnostics(raw.candidates(), chunkId, ""));
            return new BoundAttributes(Map.of(), sortDiagnostics(diagnostics));
        }

        Map<String, List<Declaration>> declarations = new TreeMap<>();
        for (String hitType : hitTypes) {
            for (OntologyProperty property : schema.relationProperties(hitType)) {
                declarations
                    .computeIfAbsent(property.identityId(), ignored -> new ArrayList<>())
                    .add(new Declaration(property.type(), hitType));
            }
        }
        return bindCandidates(
            raw.candidates(),
            chunkId,
            fallbackOwner,
            resolveDeclarations(declarations),
            diagnostics
        );
    }

    private BoundAttributes bindCandidates(
        List<RawAttribute> candidates,
        String chunkId,
        String fallbackOwner,
        Map<String, ResolvedProperty> declarations,
        List<UnmatchedLabel> diagnostics
    ) {
        Map<String, List<RawAttribute>> groups = groupByIdentity(candidates);
        Map<String, Object> values = new TreeMap<>();
        Map<String, String> rawLabels = new TreeMap<>();

        for (Map.Entry<String, List<RawAttribute>> entry : groups.entrySet()) {
            String identity = entry.getKey();
            List<RawAttribute> group = entry.getValue();
            String rawLabel = group.get(0).rawKey();
            rawLabels.put(identity, rawLabel);

            ResolvedProperty declaration = declarations.get(identity);
            if (declaration == null) {
                diagnostics.add(diagnostic(
                    chunkId, rawLabel, fallbackOwner, UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE));
                continue;
            }
            if (declaration.conflict()) {
                diagnostics.add(diagnostic(
                    chunkId, rawLabel, declaration.ownerLabel(),
                    UnmatchedLabel.Reason.ATTRIBUTE_TYPE_CONFLICT));
                continue;
            }

            List<Object> coerced = new ArrayList<>(group.size());
            boolean failed = false;
            for (RawAttribute candidate : group) {
                try {
                    coerced.add(AttributeValues.coerce(candidate.value(), declaration.type()));
                } catch (IllegalArgumentException ex) {
                    failed = true;
                    break;
                }
            }
            if (failed) {
                diagnostics.add(diagnostic(
                    chunkId, rawLabel, declaration.ownerLabel(),
                    UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE));
                continue;
            }

            Set<String> canonicalValues = new LinkedHashSet<>();
            for (Object value : coerced) {
                canonicalValues.add(AttributeJsonCodec.canonicalValueJson(value));
            }
            if (canonicalValues.size() > 1) {
                diagnostics.add(diagnostic(
                    chunkId, rawLabel, declaration.ownerLabel(),
                    UnmatchedLabel.Reason.ATTRIBUTE_DUPLICATE_KEY));
                continue;
            }
            values.put(identity, coerced.get(0));
        }

        Map<String, Object> capped = AttributeValues.applyCaps(values, declarations.keySet());
        for (String identity : values.keySet()) {
            if (capped.containsKey(identity)) {
                continue;
            }
            ResolvedProperty declaration = declarations.get(identity);
            diagnostics.add(diagnostic(
                chunkId,
                rawLabels.get(identity),
                declaration == null ? fallbackOwner : declaration.ownerLabel(),
                UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED
            ));
        }
        return new BoundAttributes(capped, sortDiagnostics(diagnostics));
    }

    private static Map<String, List<RawAttribute>> groupByIdentity(List<RawAttribute> candidates) {
        Map<String, List<RawAttribute>> groups = new TreeMap<>();
        for (RawAttribute candidate : candidates) {
            String identity = OntologyAttributeSchema.identityOrNull(candidate.rawKey());
            if (identity == null) {
                continue;
            }
            groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<RawAttribute> group : groups.values()) {
            group.sort(Comparator.comparing(RawAttribute::rawKey));
        }
        return groups;
    }

    private static List<UnmatchedLabel> undeclaredDiagnostics(
        List<RawAttribute> candidates,
        String chunkId,
        String ownerLabel
    ) {
        List<UnmatchedLabel> diagnostics = new ArrayList<>();
        for (Map.Entry<String, List<RawAttribute>> entry : groupByIdentity(candidates).entrySet()) {
            diagnostics.add(diagnostic(
                chunkId,
                entry.getValue().get(0).rawKey(),
                ownerLabel,
                UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE
            ));
        }
        return diagnostics;
    }

    private static void addRawIssues(
        List<RawIssue> issues,
        String chunkId,
        String ownerLabel,
        List<UnmatchedLabel> diagnostics
    ) {
        for (RawIssue issue : issues) {
            diagnostics.add(diagnostic(chunkId, issue.rawKey(), ownerLabel, issue.reason()));
        }
    }

    private static Map<String, ResolvedProperty> resolveDeclarations(
        Map<String, List<Declaration>> declarations
    ) {
        Map<String, ResolvedProperty> resolved = new TreeMap<>();
        declarations.forEach((identity, candidates) -> {
            Set<OntologyPropertyType> types = EnumSet.noneOf(OntologyPropertyType.class);
            String ownerLabel = "";
            for (Declaration candidate : candidates) {
                types.add(candidate.type());
                ownerLabel = ownerLabel.isEmpty()
                    ? candidate.ownerLabel()
                    : min(ownerLabel, candidate.ownerLabel());
            }
            boolean conflict = types.size() > 1;
            OntologyPropertyType type = conflict ? null : types.iterator().next();
            resolved.put(identity, new ResolvedProperty(type, ownerLabel, conflict));
        });
        return resolved;
    }

    private static UnmatchedLabel diagnostic(
        String chunkId,
        String rawLabel,
        String ownerLabel,
        UnmatchedLabel.Reason reason
    ) {
        return new UnmatchedLabel(
            UnmatchedLabel.Kind.ATTRIBUTE,
            rawLabel,
            chunkId,
            reason,
            ownerLabel
        );
    }

    private static List<UnmatchedLabel> sortDiagnostics(List<UnmatchedLabel> diagnostics) {
        List<UnmatchedLabel> sorted = new ArrayList<>(diagnostics);
        sorted.sort(DIAGNOSTIC_ORDER);
        return List.copyOf(sorted);
    }

    private static String identityOrEmpty(String label) {
        String identity = OntologyAttributeSchema.identityOrNull(label);
        return identity == null ? "" : identity;
    }

    private static String min(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private record Declaration(OntologyPropertyType type, String ownerLabel) {}

    private record ResolvedProperty(OntologyPropertyType type, String ownerLabel, boolean conflict) {}
}
