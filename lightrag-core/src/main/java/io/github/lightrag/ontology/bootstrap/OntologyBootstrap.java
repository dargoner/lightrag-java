package io.github.lightrag.ontology.bootstrap;

import io.github.lightrag.ontology.AttributeJsonCodec;
import io.github.lightrag.ontology.AttributeValues;
import io.github.lightrag.ontology.Identity;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyJsonCodec;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.RawAttribute;
import io.github.lightrag.ontology.RawAttributes;
import io.github.lightrag.ontology.RawIssue;
import io.github.lightrag.ontology.ReservedLabels;
import io.github.lightrag.ontology.UnmatchedLabel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic, zero-LLM ontology bootstrap.
 *
 * <p>The implementation follows the phase-2 plan: samples are normalized with
 * the same identity grouping rules as extraction, deduplicated by document /
 * chunk / identity keys, gated by occurrence and document counts, and then used
 * to infer entity types, relation types, patterns, and attribute types.
 *
 * <p>The result is a complete candidate ontology (all base definitions plus
 * inferred additions) that is never applied automatically. This class has no
 * storage dependency, calls no governance mutation, and never assigns a
 * version.
 *
 * <p>All output collections are sorted by identity. Consequently the complete
 * {@link BootstrapResult} is independent of input sample order and attribute map
 * iteration order.
 */
public final class OntologyBootstrap {

    /** Bootstrap algorithm version included in the sample fingerprint. */
    public static final int ALGORITHM_VERSION = 1;

    private static final String EMPTY_IDENTITY = "<empty>";

    private static final Comparator<EntityKey> ENTITY_KEY_COMPARATOR = Comparator
        .comparing(EntityKey::documentId)
        .thenComparing(EntityKey::chunkId)
        .thenComparing(EntityKey::nameIdentity)
        .thenComparing(EntityKey::typeIdentity);

    private static final Comparator<RelationKey> RELATION_KEY_COMPARATOR = (left, right) -> {
        int comparison = left.documentId().compareTo(right.documentId());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.chunkId().compareTo(right.chunkId());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.sourceIdentity().compareTo(right.sourceIdentity());
        if (comparison != 0) {
            return comparison;
        }
        comparison = left.targetIdentity().compareTo(right.targetIdentity());
        if (comparison != 0) {
            return comparison;
        }
        return compareStringLists(left.keywordIdentities(), right.keywordIdentities());
    };

    private static final Comparator<PatternKey> PATTERN_KEY_COMPARATOR = Comparator
        .comparing(PatternKey::sourceTypeIdentity)
        .thenComparing(PatternKey::targetTypeIdentity);

    private static final Comparator<BootstrapConflict> CONFLICT_COMPARATOR = Comparator
        .comparing((BootstrapConflict conflict) -> safeIdentity(conflict.ownerLabel()))
        .thenComparing(conflict -> safeIdentity(conflict.item()))
        .thenComparing(BootstrapConflict::detail);

    /** Creates a bootstrap instance. The class is stateless. */
    public OntologyBootstrap() {}

    /**
     * Bootstraps a complete ontology draft from entity and relation samples.
     *
     * @param entities entity samples; {@code null} is treated as an empty list
     * @param relations relation samples; {@code null} is treated as an empty list
     * @param options bootstrap options; must not be {@code null}
     * @return a deterministic draft with conflicts, warnings, statistics, and a
     *         sample fingerprint
     * @throws IllegalArgumentException if the sample count exceeds
     *                                  {@link BootstrapOptions#maxSamples()}
     */
    public BootstrapResult bootstrap(
        List<EntitySample> entities,
        List<RelationSample> relations,
        BootstrapOptions options
    ) {
        Objects.requireNonNull(options, "options");
        List<EntitySample> entityInput = entities == null ? List.of() : entities;
        List<RelationSample> relationInput = relations == null ? List.of() : relations;

        long sampleCount = (long) entityInput.size() + relationInput.size();
        if (sampleCount > options.maxSamples()) {
            throw new IllegalArgumentException(
                "sample count " + sampleCount + " exceeds maxSamples " + options.maxSamples()
            );
        }

        List<String> warnings = new ArrayList<>();
        List<BootstrapConflict> conflicts = new ArrayList<>();

        List<NormalizedEntity> normalizedEntities = new ArrayList<>(entityInput.size());
        for (EntitySample sample : entityInput) {
            normalizedEntities.add(normalizeEntity(sample, warnings));
        }
        List<NormalizedRelation> normalizedRelations = new ArrayList<>(relationInput.size());
        for (RelationSample sample : relationInput) {
            normalizedRelations.add(normalizeRelation(sample, warnings));
        }

        int duplicateSamples = 0;
        int conflictingDuplicateAttributes = 0;

        Map<EntityKey, List<NormalizedEntity>> rawEntityGroups = new TreeMap<>(ENTITY_KEY_COMPARATOR);
        for (NormalizedEntity row : normalizedEntities) {
            EntityKey key = new EntityKey(
                row.documentId(),
                row.chunkId(),
                row.nameIdentity(),
                row.typeIdentity()
            );
            rawEntityGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Map<EntityKey, EntityGroup> entityGroups = new TreeMap<>(ENTITY_KEY_COMPARATOR);
        for (Map.Entry<EntityKey, List<NormalizedEntity>> entry : rawEntityGroups.entrySet()) {
            duplicateSamples += entry.getValue().size() - 1;
            EntityGroup group = buildEntityGroup(entry.getKey(), entry.getValue(), conflicts);
            conflictingDuplicateAttributes += group.duplicateAttributeConflicts();
            entityGroups.put(entry.getKey(), group);
        }

        Map<RelationKey, List<NormalizedRelation>> rawRelationGroups = new TreeMap<>(RELATION_KEY_COMPARATOR);
        for (NormalizedRelation row : normalizedRelations) {
            List<String> keywordIdentities = new ArrayList<>(row.keywords().keySet());
            keywordIdentities.sort(String::compareTo);
            RelationKey key = new RelationKey(
                row.documentId(),
                row.chunkId(),
                row.sourceIdentity(),
                row.targetIdentity(),
                keywordIdentities
            );
            rawRelationGroups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Map<RelationKey, RelationGroup> relationGroups = new TreeMap<>(RELATION_KEY_COMPARATOR);
        for (Map.Entry<RelationKey, List<NormalizedRelation>> entry : rawRelationGroups.entrySet()) {
            duplicateSamples += entry.getValue().size() - 1;
            RelationGroup group = buildRelationGroup(entry.getKey(), entry.getValue(), conflicts);
            conflictingDuplicateAttributes += group.duplicateAttributeConflicts();
            relationGroups.put(entry.getKey(), group);
        }

        Map<String, Support> entityTypeSupport = new TreeMap<>();
        for (EntityGroup group : entityGroups.values()) {
            String typeIdentity = group.key().typeIdentity();
            if (isExcludedLabel(typeIdentity)) {
                continue;
            }
            Support support = entityTypeSupport.computeIfAbsent(typeIdentity, ignored -> new Support());
            support.count++;
            support.documents.add(group.key().documentId());
        }
        Map<String, List<String>> entityTypeSpellings = new TreeMap<>();
        for (Map.Entry<String, Support> entry : entityTypeSupport.entrySet()) {
            if (passesGate(entry.getValue(), options)) {
                entityTypeSpellings.put(entry.getKey(), new ArrayList<>());
            }
        }
        for (EntityGroup group : entityGroups.values()) {
            List<String> spellings = entityTypeSpellings.get(group.key().typeIdentity());
            if (spellings != null) {
                spellings.add(group.typeSpelling());
            }
        }
        Map<String, String> entityTypeLabels = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : entityTypeSpellings.entrySet()) {
            entityTypeLabels.put(entry.getKey(), representative(entry.getValue()));
        }

        Map<String, Support> relationTypeSupport = new TreeMap<>();
        for (RelationGroup group : relationGroups.values()) {
            for (String keywordIdentity : group.key().keywordIdentities()) {
                if (isExcludedLabel(keywordIdentity)) {
                    continue;
                }
                Support support = relationTypeSupport.computeIfAbsent(
                    keywordIdentity,
                    ignored -> new Support()
                );
                support.count++;
                support.documents.add(group.key().documentId());
            }
        }
        Map<String, List<String>> relationTypeSpellings = new TreeMap<>();
        for (Map.Entry<String, Support> entry : relationTypeSupport.entrySet()) {
            if (passesGate(entry.getValue(), options)) {
                relationTypeSpellings.put(entry.getKey(), new ArrayList<>());
            }
        }
        for (RelationGroup group : relationGroups.values()) {
            for (String keywordIdentity : group.key().keywordIdentities()) {
                List<String> spellings = relationTypeSpellings.get(keywordIdentity);
                if (spellings != null) {
                    spellings.add(group.keywordSpellings().get(keywordIdentity));
                }
            }
        }
        Map<String, String> relationTypeLabels = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : relationTypeSpellings.entrySet()) {
            relationTypeLabels.put(entry.getKey(), representative(entry.getValue()));
        }

        Map<String, Set<String>> globalEntityTypes = new TreeMap<>();
        Map<ScopeKey, Set<String>> scopedEntityTypes = new TreeMap<>();
        for (EntityGroup group : entityGroups.values()) {
            String typeIdentity = group.key().typeIdentity();
            if (!entityTypeLabels.containsKey(typeIdentity)) {
                continue;
            }
            globalEntityTypes
                .computeIfAbsent(group.key().nameIdentity(), ignored -> new TreeSet<>())
                .add(typeIdentity);
            ScopeKey scopeKey = new ScopeKey(
                group.key().documentId(),
                group.key().chunkId(),
                group.key().nameIdentity()
            );
            scopedEntityTypes
                .computeIfAbsent(scopeKey, ignored -> new TreeSet<>())
                .add(typeIdentity);
        }

        Map<String, Map<PatternKey, Support>> patternSupports = new TreeMap<>();
        int droppedUngatedEndpoints = 0;
        int droppedAmbiguousEndpoints = 0;
        for (RelationGroup group : relationGroups.values()) {
            EndpointResolution source = resolveEndpoint(
                group.key().sourceIdentity(),
                group.key().documentId(),
                group.key().chunkId(),
                scopedEntityTypes,
                globalEntityTypes
            );
            EndpointResolution target = resolveEndpoint(
                group.key().targetIdentity(),
                group.key().documentId(),
                group.key().chunkId(),
                scopedEntityTypes,
                globalEntityTypes
            );

            droppedAmbiguousEndpoints += countAmbiguous(
                source,
                "source",
                group,
                conflicts
            );
            droppedAmbiguousEndpoints += countAmbiguous(
                target,
                "target",
                group,
                conflicts
            );
            droppedUngatedEndpoints += countUngated(source);
            droppedUngatedEndpoints += countUngated(target);

            if (source.typeIdentity() == null || target.typeIdentity() == null) {
                continue;
            }
            PatternKey pattern = new PatternKey(source.typeIdentity(), target.typeIdentity());
            for (String keywordIdentity : group.key().keywordIdentities()) {
                if (!relationTypeLabels.containsKey(keywordIdentity)) {
                    continue;
                }
                Support support = patternSupports
                    .computeIfAbsent(keywordIdentity, ignored -> new TreeMap<>())
                    .computeIfAbsent(pattern, ignored -> new Support());
                support.count++;
                support.documents.add(group.key().documentId());
            }
        }

        Map<String, List<PatternKey>> passingPatterns = new TreeMap<>();
        for (String relationTypeIdentity : relationTypeLabels.keySet()) {
            Map<PatternKey, Support> supports = patternSupports.getOrDefault(
                relationTypeIdentity,
                Map.of()
            );
            List<PatternKey> passing = supports.entrySet().stream()
                .filter(entry -> passesGate(entry.getValue(), options))
                .map(Map.Entry::getKey)
                .sorted(PATTERN_KEY_COMPARATOR)
                .toList();
            if (!passing.isEmpty()) {
                passingPatterns.put(relationTypeIdentity, passing);
            }
        }
        int droppedUnconstrainedRelationTypes =
            relationTypeLabels.size() - passingPatterns.size();

        Map<String, List<OntologyProperty>> inferredEntityProperties = new TreeMap<>();
        Map<String, List<OntologyProperty>> inferredRelationProperties = new TreeMap<>();
        if (options.includeAttributes()) {
            Map<String, Map<String, OntologyProperty>> baseEntityProperties =
                entityPropertyIndex(options.baseOntology());
            for (String ownerIdentity : entityTypeLabels.keySet()) {
                Map<String, AttributeSupport> supports = new TreeMap<>();
                for (EntityGroup group : entityGroups.values()) {
                    if (group.key().typeIdentity().equals(ownerIdentity)) {
                        addAttributeSupports(supports, group.attributes(), group.attributeSpellings());
                    }
                }
                inferredEntityProperties.put(
                    ownerIdentity,
                    inferProperties(ownerIdentity, supports, options, baseEntityProperties, conflicts)
                );
            }

            Map<String, Map<String, OntologyProperty>> baseRelationProperties =
                relationPropertyIndex(options.baseOntology());
            for (String ownerIdentity : passingPatterns.keySet()) {
                Map<String, AttributeSupport> supports = new TreeMap<>();
                for (RelationGroup group : relationGroups.values()) {
                    if (group.key().keywordIdentities().contains(ownerIdentity)) {
                        addAttributeSupports(supports, group.attributes(), group.attributeSpellings());
                    }
                }
                inferredRelationProperties.put(
                    ownerIdentity,
                    inferProperties(ownerIdentity, supports, options, baseRelationProperties, conflicts)
                );
            }
        }

        Map<String, List<OntologyPattern>> inferredRelationPatterns = new TreeMap<>();
        for (Map.Entry<String, List<PatternKey>> entry : passingPatterns.entrySet()) {
            List<OntologyPattern> patterns = new ArrayList<>();
            for (PatternKey pattern : entry.getValue()) {
                String sourceLabel = entityTypeLabels.get(pattern.sourceTypeIdentity());
                String targetLabel = entityTypeLabels.get(pattern.targetTypeIdentity());
                if (sourceLabel != null && targetLabel != null) {
                    patterns.add(new OntologyPattern(sourceLabel, targetLabel));
                }
            }
            inferredRelationPatterns.put(entry.getKey(), patterns);
        }

        Ontology draft = buildDraft(
            options.baseOntology(),
            entityTypeLabels,
            inferredEntityProperties,
            relationTypeLabels,
            inferredRelationPatterns,
            inferredRelationProperties
        );

        List<String> sortedWarnings = warnings.stream().sorted().toList();
        List<BootstrapConflict> sortedConflicts = conflicts.stream()
            .map(conflict -> new BootstrapConflict(
                safeIdentity(conflict.ownerLabel()),
                safeIdentity(conflict.item()),
                conflict.detail()
            ))
            .distinct()
            .sorted(CONFLICT_COMPARATOR)
            .toList();

        String fingerprint = fingerprint(
            options,
            canonicalSamples(normalizedEntities, normalizedRelations)
        );
        BootstrapStats stats = new BootstrapStats(
            entityInput.size(),
            relationInput.size(),
            duplicateSamples,
            conflictingDuplicateAttributes,
            droppedUngatedEndpoints,
            droppedAmbiguousEndpoints,
            droppedUnconstrainedRelationTypes
        );
        return new BootstrapResult(
            draft,
            sortedConflicts,
            sortedWarnings,
            fingerprint,
            ALGORITHM_VERSION,
            stats
        );
    }

    private static NormalizedEntity normalizeEntity(EntitySample sample, List<String> warnings) {
        String nameIdentity = Identity.identityId(sample.name());
        String typeIdentity = Identity.identityId(sample.type());
        NormalizedAttributes attributes = normalizeAttributes(
            sample.attributes(),
            sample.documentId(),
            sample.chunkId(),
            "entity:" + sample.name(),
            warnings
        );
        return new NormalizedEntity(
            sample.name(),
            nameIdentity,
            sample.type(),
            typeIdentity,
            attributes.values(),
            attributes.spellings(),
            sample.documentId(),
            sample.chunkId()
        );
    }

    private static NormalizedRelation normalizeRelation(RelationSample sample, List<String> warnings) {
        String sourceIdentity = Identity.identityId(sample.sourceEntityName());
        String targetIdentity = Identity.identityId(sample.targetEntityName());
        NormalizedAttributes attributes = normalizeAttributes(
            sample.attributes(),
            sample.documentId(),
            sample.chunkId(),
            "relation:" + sample.sourceEntityName() + "->" + sample.targetEntityName(),
            warnings
        );
        return new NormalizedRelation(
            sample.sourceEntityName(),
            sourceIdentity,
            sample.targetEntityName(),
            targetIdentity,
            parseKeywords(sample.keywords()),
            attributes.values(),
            attributes.spellings(),
            sample.documentId(),
            sample.chunkId()
        );
    }

    private static Map<String, String> parseKeywords(String rawKeywords) {
        if (rawKeywords == null || rawKeywords.isBlank()) {
            return Map.of();
        }
        Map<String, List<String>> spellingsByIdentity = new TreeMap<>();
        for (String part : rawKeywords.split(",", -1)) {
            String candidate = part.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            String identity = Identity.identityId(candidate);
            spellingsByIdentity
                .computeIfAbsent(identity, ignored -> new ArrayList<>())
                .add(candidate);
        }
        Map<String, String> keywords = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : spellingsByIdentity.entrySet()) {
            keywords.put(entry.getKey(), representative(entry.getValue()));
        }
        return Collections.unmodifiableMap(keywords);
    }

    private static NormalizedAttributes normalizeAttributes(
        Map<String, Object> rawAttributes,
        String documentId,
        String chunkId,
        String sampleLabel,
        List<String> warnings
    ) {
        Map<String, Object> input = rawAttributes == null ? Map.of() : rawAttributes;
        RawAttributes sanitized = AttributeValues.sanitizeRaw(input);
        List<RawIssue> issues = sanitized == null || sanitized.issues() == null
            ? List.of()
            : sanitized.issues();
        for (RawIssue issue : issues) {
            warnings.add(attributeIssueWarning(documentId, chunkId, sampleLabel, issue));
        }

        boolean limitExceeded = issues.stream().anyMatch(issue -> issue != null
            && issue.reason() == UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED);
        Map<String, List<String>> rawSpellingsByIdentity = new TreeMap<>();
        if (!limitExceeded) {
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                String rawKey = entry.getKey() == null ? "" : entry.getKey().strip();
                if (!isUsableRawKey(rawKey)) {
                    continue;
                }
                rawSpellingsByIdentity
                    .computeIfAbsent(Identity.identityId(rawKey), ignored -> new ArrayList<>())
                    .add(rawKey);
            }
        }

        List<RawAttribute> candidates = sanitized == null || sanitized.candidates() == null
            ? List.of()
            : sanitized.candidates();
        Map<String, List<RawAttribute>> attributesByIdentity = new TreeMap<>();
        for (RawAttribute candidate : candidates) {
            if (candidate == null || candidate.rawKey() == null) {
                continue;
            }
            String trimmedKey = candidate.rawKey().strip();
            if (trimmedKey.isEmpty()) {
                continue;
            }
            String identity = Identity.identityId(trimmedKey);
            attributesByIdentity
                .computeIfAbsent(identity, ignored -> new ArrayList<>())
                .add(candidate);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> spellings = new LinkedHashMap<>();
        for (Map.Entry<String, List<RawAttribute>> entry : attributesByIdentity.entrySet()) {
            List<RawAttribute> group = entry.getValue();
            String firstValue = canonicalValue(group.get(0).value());
            boolean valuesAgree = true;
            for (int index = 1; index < group.size(); index++) {
                if (!canonicalValue(group.get(index).value()).equals(firstValue)) {
                    valuesAgree = false;
                    break;
                }
            }
            if (!valuesAgree) {
                warnings.add(duplicateSampleAttributeWarning(
                    documentId,
                    chunkId,
                    sampleLabel,
                    entry.getKey()
                ));
                continue;
            }
            RawAttribute chosen = group.get(0);
            for (int index = 1; index < group.size(); index++) {
                RawAttribute candidate = group.get(index);
                int comparison = candidate.rawKey().compareTo(chosen.rawKey());
                if (comparison < 0
                    || (comparison == 0
                    && canonicalValue(candidate.value()).compareTo(canonicalValue(chosen.value())) < 0)) {
                    chosen = candidate;
                }
            }
            values.put(entry.getKey(), chosen.value());
            spellings.put(
                entry.getKey(),
                representative(group.stream().map(RawAttribute::rawKey).toList())
            );
        }
        for (Map.Entry<String, List<String>> entry : rawSpellingsByIdentity.entrySet()) {
            if (!attributesByIdentity.containsKey(entry.getKey())) {
                // sanitizeRaw drops null and structurally unusable values. Keep a
                // null placeholder so step 7 can report all-unusable attributes
                // and so a fully-null key never silently counts as support.
                values.put(entry.getKey(), null);
                spellings.put(entry.getKey(), representative(entry.getValue()));
            }
        }
        return new NormalizedAttributes(
            Collections.unmodifiableMap(new TreeMap<>(values)),
            Collections.unmodifiableMap(new TreeMap<>(spellings))
        );
    }

    private static EntityGroup buildEntityGroup(
        EntityKey key,
        List<NormalizedEntity> rows,
        List<BootstrapConflict> conflicts
    ) {
        List<String> typeSpellings = new ArrayList<>(rows.size());
        List<Map<String, Object>> attributes = new ArrayList<>(rows.size());
        List<Map<String, String>> attributeSpellings = new ArrayList<>(rows.size());
        for (NormalizedEntity row : rows) {
            typeSpellings.add(row.type());
            attributes.add(row.attributes());
            attributeSpellings.add(row.spellings());
        }
        MergedAttributes merged = mergeAttributes(
            attributes,
            attributeSpellings,
            key.nameIdentity(),
            "documentId=" + key.documentId()
                + ", chunkId=" + key.chunkId()
                + ", type=" + key.typeIdentity(),
            conflicts
        );
        return new EntityGroup(
            key,
            representative(typeSpellings),
            merged.values(),
            merged.spellings(),
            merged.conflicts()
        );
    }

    private static RelationGroup buildRelationGroup(
        RelationKey key,
        List<NormalizedRelation> rows,
        List<BootstrapConflict> conflicts
    ) {
        List<Map<String, Object>> attributes = new ArrayList<>(rows.size());
        List<Map<String, String>> attributeSpellings = new ArrayList<>(rows.size());
        Map<String, List<String>> keywordSpellingLists = new TreeMap<>();
        for (NormalizedRelation row : rows) {
            attributes.add(row.attributes());
            attributeSpellings.add(row.spellings());
            for (Map.Entry<String, String> keyword : row.keywords().entrySet()) {
                keywordSpellingLists
                    .computeIfAbsent(keyword.getKey(), ignored -> new ArrayList<>())
                    .add(keyword.getValue());
            }
        }
        MergedAttributes merged = mergeAttributes(
            attributes,
            attributeSpellings,
            sourceTargetIdentity(key),
            "documentId=" + key.documentId()
                + ", chunkId=" + key.chunkId()
                + ", keywords=" + String.join(",", key.keywordIdentities()),
            conflicts
        );
        Map<String, String> keywordSpellings = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : keywordSpellingLists.entrySet()) {
            keywordSpellings.put(entry.getKey(), representative(entry.getValue()));
        }
        return new RelationGroup(
            key,
            keywordSpellings,
            merged.values(),
            merged.spellings(),
            merged.conflicts()
        );
    }

    private static MergedAttributes mergeAttributes(
        List<Map<String, Object>> attributesByRow,
        List<Map<String, String>> spellingsByRow,
        String ownerIdentity,
        String context,
        List<BootstrapConflict> conflicts
    ) {
        Set<String> allIdentities = new TreeSet<>();
        for (Map<String, Object> attributes : attributesByRow) {
            allIdentities.addAll(attributes.keySet());
        }

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> spellings = new LinkedHashMap<>();
        int conflictingAttributes = 0;
        for (String identity : allIdentities) {
            List<Object> presentValues = new ArrayList<>();
            List<String> presentSpellings = new ArrayList<>();
            for (int index = 0; index < attributesByRow.size(); index++) {
                Map<String, Object> attributes = attributesByRow.get(index);
                if (attributes.containsKey(identity)) {
                    presentValues.add(attributes.get(identity));
                    presentSpellings.add(spellingsByRow.get(index).get(identity));
                }
            }
            if (presentValues.isEmpty()) {
                continue;
            }

            // sanitizeRaw drops null values, so a null entry only exists as the
            // placeholder used to report all-unusable keys. Treat it as absent
            // while any usable value exists; it never overrides or conflicts
            // with a real value.
            List<Object> usableValues = new ArrayList<>();
            List<String> usableSpellings = new ArrayList<>();
            for (int index = 0; index < presentValues.size(); index++) {
                Object value = presentValues.get(index);
                if (value != null) {
                    usableValues.add(value);
                    usableSpellings.add(presentSpellings.get(index));
                }
            }
            if (usableValues.isEmpty()) {
                values.put(identity, null);
                spellings.put(identity, representative(presentSpellings));
                continue;
            }

            String firstValue = canonicalValue(usableValues.get(0));
            boolean valuesAgree = true;
            for (int index = 1; index < usableValues.size(); index++) {
                if (!canonicalValue(usableValues.get(index)).equals(firstValue)) {
                    valuesAgree = false;
                    break;
                }
            }
            if (!valuesAgree) {
                conflictingAttributes++;
                conflicts.add(new BootstrapConflict(
                    ownerIdentity,
                    identity,
                    "duplicate attribute values conflict (" + context + "); key dropped"
                ));
                continue;
            }

            int chosenIndex = 0;
            for (int index = 1; index < usableSpellings.size(); index++) {
                String spelling = usableSpellings.get(index) == null
                    ? ""
                    : usableSpellings.get(index);
                String chosenSpelling = usableSpellings.get(chosenIndex) == null
                    ? ""
                    : usableSpellings.get(chosenIndex);
                if (spelling.compareTo(chosenSpelling) < 0) {
                    chosenIndex = index;
                }
            }
            values.put(identity, usableValues.get(chosenIndex));
            spellings.put(identity, representative(usableSpellings));
        }
        return new MergedAttributes(
            Collections.unmodifiableMap(values),
            Collections.unmodifiableMap(spellings),
            conflictingAttributes
        );
    }

    private static void addAttributeSupports(
        Map<String, AttributeSupport> supports,
        Map<String, Object> attributes,
        Map<String, String> spellings
    ) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            AttributeSupport support = supports.computeIfAbsent(
                entry.getKey(),
                ignored -> new AttributeSupport()
            );
            support.count++;
            support.values.add(entry.getValue());
            support.spellings.add(spellings.get(entry.getKey()));
        }
    }

    private static List<OntologyProperty> inferProperties(
        String ownerIdentity,
        Map<String, AttributeSupport> supports,
        BootstrapOptions options,
        Map<String, Map<String, OntologyProperty>> baseProperties,
        List<BootstrapConflict> conflicts
    ) {
        Map<String, OntologyProperty> ownerBaseProperties = baseProperties.getOrDefault(
            ownerIdentity,
            Map.of()
        );
        List<OntologyProperty> inferred = new ArrayList<>();
        for (Map.Entry<String, AttributeSupport> entry : supports.entrySet()) {
            AttributeSupport support = entry.getValue();
            if (support.count < options.minAttributeSupport()) {
                continue;
            }
            List<Object> usableValues = support.values.stream()
                .filter(Objects::nonNull)
                .toList();
            if (usableValues.isEmpty()) {
                conflicts.add(new BootstrapConflict(
                    ownerIdentity,
                    entry.getKey(),
                    "all attribute values are null or unusable; property skipped"
                ));
                continue;
            }
            OntologyPropertyType type = inferType(usableValues);
            if (type == null) {
                continue;
            }
            OntologyProperty baseProperty = ownerBaseProperties.get(entry.getKey());
            if (baseProperty != null) {
                if (baseProperty.type() != type) {
                    conflicts.add(new BootstrapConflict(
                        ownerIdentity,
                        entry.getKey(),
                        "inferred type " + type + " conflicts with base type "
                            + baseProperty.type() + "; base definition retained"
                    ));
                }
                continue;
            }
            String name = representative(support.spellings);
            if (name.isEmpty()) {
                name = entry.getKey();
            }
            inferred.add(new OntologyProperty(name, type, null, null));
        }
        return inferred;
    }

    private static OntologyPropertyType inferType(List<Object> values) {
        if (values.isEmpty()) {
            return null;
        }
        boolean allBoolean = values.stream().allMatch(value -> value instanceof Boolean);
        boolean allBooleanStrings = values.stream().allMatch(
            value -> value instanceof String text && isBooleanLiteral(text)
        );
        if (allBoolean || allBooleanStrings) {
            return OntologyPropertyType.BOOLEAN;
        }
        if (values.stream().allMatch(OntologyBootstrap::isIntegralNumber)) {
            return OntologyPropertyType.INTEGER;
        }
        if (values.stream().allMatch(value -> value instanceof Number)) {
            return OntologyPropertyType.FLOAT;
        }
        if (values.stream().anyMatch(value -> value instanceof List<?>)) {
            return OntologyPropertyType.LIST;
        }
        if (values.stream().allMatch(value -> value instanceof String text && isIsoDate(text))) {
            return OntologyPropertyType.DATE;
        }
        return OntologyPropertyType.STRING;
    }

    private static boolean isBooleanLiteral(String value) {
        return "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value);
    }

    private static boolean isIntegralNumber(Object value) {
        if (value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof BigInteger) {
            return true;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().scale() <= 0;
        }
        return false;
    }

    private static boolean isIsoDate(String value) {
        String candidate = value.strip();
        if (candidate.isEmpty()) {
            return false;
        }
        try {
            LocalDate.parse(candidate);
            return true;
        } catch (DateTimeParseException ignored) {
            // Try the datetime forms below.
        }
        try {
            OffsetDateTime.parse(candidate);
            return true;
        } catch (DateTimeParseException ignored) {
            // Try a local datetime without an offset.
        }
        try {
            LocalDateTime.parse(candidate);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private static EndpointResolution resolveEndpoint(
        String nameIdentity,
        String documentId,
        String chunkId,
        Map<ScopeKey, Set<String>> scopedEntityTypes,
        Map<String, Set<String>> globalEntityTypes
    ) {
        Set<String> local = scopedEntityTypes.get(
            new ScopeKey(documentId, chunkId, nameIdentity)
        );
        if (local != null && !local.isEmpty()) {
            return classifyEndpoint(local);
        }
        Set<String> global = globalEntityTypes.get(nameIdentity);
        if (global == null || global.isEmpty()) {
            return EndpointResolution.unresolved();
        }
        return classifyEndpoint(global);
    }

    private static EndpointResolution classifyEndpoint(Set<String> typeIdentities) {
        if (typeIdentities.size() == 1) {
            return EndpointResolution.resolved(typeIdentities.iterator().next());
        }
        return EndpointResolution.ambiguous(List.copyOf(typeIdentities));
    }

    private static int countAmbiguous(
        EndpointResolution resolution,
        String role,
        RelationGroup group,
        List<BootstrapConflict> conflicts
    ) {
        if (!resolution.ambiguous()) {
            return 0;
        }
        conflicts.add(new BootstrapConflict(
            sourceTargetIdentity(group.key()),
            role.equals("source") ? group.key().sourceIdentity() : group.key().targetIdentity(),
            "ambiguous " + role + " endpoint types "
                + String.join(",", resolution.candidates())
                + " (documentId=" + group.key().documentId()
                + ", chunkId=" + group.key().chunkId() + ")"
        ));
        return 1;
    }

    private static int countUngated(EndpointResolution resolution) {
        return resolution.ungated() ? 1 : 0;
    }

    private static Ontology buildDraft(
        Ontology base,
        Map<String, String> entityTypeLabels,
        Map<String, List<OntologyProperty>> entityProperties,
        Map<String, String> relationTypeLabels,
        Map<String, List<OntologyPattern>> relationPatterns,
        Map<String, List<OntologyProperty>> relationProperties
    ) {
        Map<String, OntologyEntityType> baseEntities = new TreeMap<>();
        Map<String, OntologyRelationType> baseRelations = new TreeMap<>();
        if (base != null) {
            for (OntologyEntityType type : base.entityTypes()) {
                baseEntities.putIfAbsent(type.identityId(), type);
            }
            for (OntologyRelationType type : base.relationTypes()) {
                baseRelations.putIfAbsent(type.identityId(), type);
            }
        }

        Set<String> entityIdentities = new TreeSet<>(baseEntities.keySet());
        entityIdentities.addAll(entityTypeLabels.keySet());
        List<OntologyEntityType> entityTypes = new ArrayList<>(entityIdentities.size());
        for (String identity : entityIdentities) {
            OntologyEntityType baseType = baseEntities.get(identity);
            List<OntologyProperty> properties = baseType == null
                ? dedupeAndSortProperties(entityProperties.getOrDefault(identity, List.of()))
                : mergeProperties(
                    baseType.properties(),
                    entityProperties.getOrDefault(identity, List.of())
                );
            entityTypes.add(baseType == null
                ? new OntologyEntityType(entityTypeLabels.get(identity), null, properties, null)
                : new OntologyEntityType(
                    baseType.label(),
                    baseType.description(),
                    properties,
                    baseType.sourceBinding()
                ));
        }

        Set<String> relationIdentities = new TreeSet<>(baseRelations.keySet());
        relationIdentities.addAll(relationPatterns.keySet());
        List<OntologyRelationType> relationTypes = new ArrayList<>(relationIdentities.size());
        for (String identity : relationIdentities) {
            OntologyRelationType baseType = baseRelations.get(identity);
            List<OntologyPattern> patterns = baseType == null
                ? dedupeAndSortPatterns(relationPatterns.getOrDefault(identity, List.of()))
                : mergePatterns(
                    baseType.patterns(),
                    relationPatterns.getOrDefault(identity, List.of())
                );
            List<OntologyProperty> properties = baseType == null
                ? dedupeAndSortProperties(relationProperties.getOrDefault(identity, List.of()))
                : mergeProperties(
                    baseType.properties(),
                    relationProperties.getOrDefault(identity, List.of())
                );
            relationTypes.add(baseType == null
                ? new OntologyRelationType(
                    relationTypeLabels.get(identity),
                    null,
                    patterns,
                    properties,
                    null
                )
                : new OntologyRelationType(
                    baseType.label(),
                    baseType.description(),
                    patterns,
                    properties,
                    baseType.sourceBinding()
                ));
        }

        Map<String, String> metadata = base == null ? Map.of() : base.metadata();
        return new Ontology(entityTypes, relationTypes, null, metadata);
    }

    private static Map<String, Map<String, OntologyProperty>> entityPropertyIndex(Ontology base) {
        Map<String, Map<String, OntologyProperty>> index = new TreeMap<>();
        if (base == null) {
            return index;
        }
        for (OntologyEntityType type : base.entityTypes()) {
            Map<String, OntologyProperty> properties = new TreeMap<>();
            for (OntologyProperty property : type.properties()) {
                properties.putIfAbsent(property.identityId(), property);
            }
            index.putIfAbsent(type.identityId(), properties);
        }
        return index;
    }

    private static Map<String, Map<String, OntologyProperty>> relationPropertyIndex(Ontology base) {
        Map<String, Map<String, OntologyProperty>> index = new TreeMap<>();
        if (base == null) {
            return index;
        }
        for (OntologyRelationType type : base.relationTypes()) {
            Map<String, OntologyProperty> properties = new TreeMap<>();
            for (OntologyProperty property : type.properties()) {
                properties.putIfAbsent(property.identityId(), property);
            }
            index.putIfAbsent(type.identityId(), properties);
        }
        return index;
    }

    private static List<OntologyProperty> mergeProperties(
        List<OntologyProperty> baseProperties,
        List<OntologyProperty> additions
    ) {
        Map<String, OntologyProperty> merged = new TreeMap<>();
        for (OntologyProperty property : baseProperties) {
            merged.putIfAbsent(property.identityId(), property);
        }
        for (OntologyProperty property : additions) {
            merged.putIfAbsent(property.identityId(), property);
        }
        return List.copyOf(merged.values());
    }

    private static List<OntologyPattern> mergePatterns(
        List<OntologyPattern> basePatterns,
        List<OntologyPattern> additions
    ) {
        Map<String, OntologyPattern> merged = new TreeMap<>();
        for (OntologyPattern pattern : basePatterns) {
            merged.putIfAbsent(pattern.identityId(), pattern);
        }
        for (OntologyPattern pattern : additions) {
            merged.putIfAbsent(pattern.identityId(), pattern);
        }
        return List.copyOf(merged.values());
    }

    private static List<OntologyProperty> dedupeAndSortProperties(
        List<OntologyProperty> properties
    ) {
        Map<String, OntologyProperty> sorted = new TreeMap<>();
        for (OntologyProperty property : properties) {
            sorted.putIfAbsent(property.identityId(), property);
        }
        return List.copyOf(sorted.values());
    }

    private static List<OntologyPattern> dedupeAndSortPatterns(List<OntologyPattern> patterns) {
        Map<String, OntologyPattern> sorted = new TreeMap<>();
        for (OntologyPattern pattern : patterns) {
            sorted.putIfAbsent(pattern.identityId(), pattern);
        }
        return List.copyOf(sorted.values());
    }

    private static List<String> canonicalSamples(
        List<NormalizedEntity> entities,
        List<NormalizedRelation> relations
    ) {
        Set<String> samples = new TreeSet<>();
        for (NormalizedEntity entity : entities) {
            samples.add(canonicalEntitySample(entity));
        }
        for (NormalizedRelation relation : relations) {
            samples.add(canonicalRelationSample(relation));
        }
        return List.copyOf(samples);
    }

    private static String canonicalEntitySample(NormalizedEntity entity) {
        Map<String, Object> sample = new TreeMap<>();
        sample.put("kind", "entity");
        sample.put("documentId", entity.documentId());
        sample.put("chunkId", entity.chunkId());
        sample.put("name", entity.name());
        sample.put("type", entity.type());
        sample.put("attributes", fingerprintAttributes(entity.attributes()));
        return AttributeJsonCodec.toJson(sample);
    }

    private static String canonicalRelationSample(NormalizedRelation relation) {
        Map<String, Object> sample = new TreeMap<>();
        sample.put("kind", "relation");
        sample.put("documentId", relation.documentId());
        sample.put("chunkId", relation.chunkId());
        sample.put("sourceEntityName", relation.source());
        sample.put("targetEntityName", relation.target());
        sample.put(
            "keywords",
            relation.keywords().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList()
        );
        sample.put("attributes", fingerprintAttributes(relation.attributes()));
        return AttributeJsonCodec.toJson(sample);
    }

    private static Map<String, Object> fingerprintAttributes(Map<String, Object> attributes) {
        Map<String, Object> view = new TreeMap<>();
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            view.put(entry.getKey(), canonicalValue(entry.getValue()));
        }
        return view;
    }

    private static String fingerprint(BootstrapOptions options, List<String> samples) {
        Map<String, Object> root = new TreeMap<>();
        root.put("algorithmVersion", ALGORITHM_VERSION);
        root.put(
            "base",
            options.baseOntology() == null
                ? ""
                : OntologyJsonCodec.fullContentHash(options.baseOntology())
        );
        Map<String, Object> optionView = new TreeMap<>();
        optionView.put("includeAttributes", options.includeAttributes());
        optionView.put("maxSamples", options.maxSamples());
        optionView.put("minAttributeSupport", options.minAttributeSupport());
        optionView.put("minDocuments", options.minDocuments());
        optionView.put("minOccurrences", options.minOccurrences());
        root.put("options", optionView);
        root.put("samples", samples);
        return sha256Hex(AttributeJsonCodec.toJson(root));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available", exception);
        }
    }

    private static String attributeIssueWarning(
        String documentId,
        String chunkId,
        String sampleLabel,
        RawIssue issue
    ) {
        String reason = issue == null || issue.reason() == null
            ? "UNKNOWN"
            : issue.reason().name();
        String attribute = issue == null ? EMPTY_IDENTITY : safeIdentity(issue.rawKey());
        return "attribute issue: documentId=" + documentId
            + ", chunkId=" + chunkId
            + ", sample=" + sampleLabel
            + ", attribute=" + attribute
            + ", reason=" + reason;
    }

    private static String duplicateSampleAttributeWarning(
        String documentId,
        String chunkId,
        String sampleLabel,
        String attributeIdentity
    ) {
        return "attribute conflict: documentId=" + documentId
            + ", chunkId=" + chunkId
            + ", sample=" + sampleLabel
            + ", attribute=" + attributeIdentity
            + " (multiple values for the same key identity; key dropped)";
    }

    private static String canonicalValue(Object value) {
        if (value == null) {
            return "\u0000null";
        }
        try {
            return AttributeJsonCodec.toJson(Collections.singletonMap("value", value));
        } catch (RuntimeException exception) {
            return "\u0000invalid:" + value.getClass().getName();
        }
    }

    private static String representative(List<String> spellings) {
        if (spellings == null || spellings.isEmpty()) {
            return "";
        }
        Map<String, Integer> counts = new TreeMap<>();
        for (String spelling : spellings) {
            counts.merge(spelling == null ? "" : spelling, 1, Integer::sum);
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best == null ? "" : best;
    }

    private static boolean passesGate(Support support, BootstrapOptions options) {
        return support.count >= options.minOccurrences()
            && support.documents.size() >= options.minDocuments();
    }

    private static boolean isExcludedLabel(String identity) {
        return ReservedLabels.isKernelReserved(identity)
            || ReservedLabels.isExtractionFallback(identity);
    }

    private static boolean isUsableRawKey(String key) {
        if (key == null
            || key.isEmpty()
            || key.length() > AttributeValues.MAX_KEY_LENGTH) {
            return false;
        }
        for (int index = 0; index < key.length(); index++) {
            if (Character.isISOControl(key.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static String sourceTargetIdentity(RelationKey key) {
        return key.sourceIdentity() + "->" + key.targetIdentity();
    }

    private static String safeIdentity(String label) {
        if (label == null || label.strip().isEmpty()) {
            return EMPTY_IDENTITY;
        }
        return Identity.identityId(label);
    }

    private static int compareStringLists(List<String> left, List<String> right) {
        int length = Math.min(left.size(), right.size());
        for (int index = 0; index < length; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record EntityKey(
        String documentId,
        String chunkId,
        String nameIdentity,
        String typeIdentity
    ) {}

    private record RelationKey(
        String documentId,
        String chunkId,
        String sourceIdentity,
        String targetIdentity,
        List<String> keywordIdentities
    ) {
        private RelationKey {
            keywordIdentities = List.copyOf(keywordIdentities);
        }
    }

    private record ScopeKey(
        String documentId,
        String chunkId,
        String nameIdentity
    ) implements Comparable<ScopeKey> {
        @Override
        public int compareTo(ScopeKey other) {
            int result = documentId.compareTo(other.documentId);
            if (result != 0) {
                return result;
            }
            result = chunkId.compareTo(other.chunkId);
            if (result != 0) {
                return result;
            }
            return nameIdentity.compareTo(other.nameIdentity);
        }
    }

    private record PatternKey(
        String sourceTypeIdentity,
        String targetTypeIdentity
    ) implements Comparable<PatternKey> {
        @Override
        public int compareTo(PatternKey other) {
            int result = sourceTypeIdentity.compareTo(other.sourceTypeIdentity);
            if (result != 0) {
                return result;
            }
            return targetTypeIdentity.compareTo(other.targetTypeIdentity);
        }
    }

    private record NormalizedEntity(
        String name,
        String nameIdentity,
        String type,
        String typeIdentity,
        Map<String, Object> attributes,
        Map<String, String> spellings,
        String documentId,
        String chunkId
    ) {}

    private record NormalizedRelation(
        String source,
        String sourceIdentity,
        String target,
        String targetIdentity,
        Map<String, String> keywords,
        Map<String, Object> attributes,
        Map<String, String> spellings,
        String documentId,
        String chunkId
    ) {}

    private record NormalizedAttributes(
        Map<String, Object> values,
        Map<String, String> spellings
    ) {}

    private record MergedAttributes(
        Map<String, Object> values,
        Map<String, String> spellings,
        int conflicts
    ) {}

    private record EntityGroup(
        EntityKey key,
        String typeSpelling,
        Map<String, Object> attributes,
        Map<String, String> attributeSpellings,
        int duplicateAttributeConflicts
    ) {}

    private record RelationGroup(
        RelationKey key,
        Map<String, String> keywordSpellings,
        Map<String, Object> attributes,
        Map<String, String> attributeSpellings,
        int duplicateAttributeConflicts
    ) {}

    private record EndpointResolution(
        String typeIdentity,
        boolean ambiguous,
        boolean ungated,
        List<String> candidates
    ) {
        private EndpointResolution {
            candidates = List.copyOf(candidates);
        }

        private static EndpointResolution resolved(String typeIdentity) {
            return new EndpointResolution(typeIdentity, false, false, List.of(typeIdentity));
        }

        private static EndpointResolution ambiguous(List<String> candidates) {
            return new EndpointResolution(null, true, false, candidates);
        }

        private static EndpointResolution unresolved() {
            return new EndpointResolution(null, false, true, List.of());
        }
    }

    private static final class Support {
        private int count;
        private final Set<String> documents = new TreeSet<>();
    }

    private static final class AttributeSupport {
        private int count;
        private final List<Object> values = new ArrayList<>();
        private final List<String> spellings = new ArrayList<>();
    }
}
