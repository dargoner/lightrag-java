package io.github.lightrag.ontology;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stateless, pure-function ontology governance utilities.
 *
 * <p>This class performs subset checks, contradiction detection, candidate
 * synthesis, content hashing, and structural diffing without holding any state
 * or depending on storage.
 *
 * <p>All comparisons use {@link Identity#identityId(String)}, never raw labels.
 */
public final class OntologyGovernance {

    private OntologyGovernance() {}

    /**
     * Returns true when every entity, relation, property, and pattern in the
     * candidate exists in the base ontology and is compatible.
     *
     * <p>Compatibility requires that candidate properties are a subset of base
     * properties (by identity and type), and that candidate relation patterns
     * are a subset of base relation patterns. Equal ontologies are subsets of
     * each other.
     *
     * @param candidate the candidate ontology
     * @param base      the base ontology
     * @return true if the candidate is a subset of the base
     */
    public static boolean isSubset(Ontology candidate, Ontology base) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(base, "base");

        Map<String, OntologyEntityType> baseEntities = indexByIdentity(
            base.entityTypes(),
            OntologyEntityType::identityId
        );
        for (OntologyEntityType candidateEntity : candidate.entityTypes()) {
            OntologyEntityType baseEntity = baseEntities.get(candidateEntity.identityId());
            if (baseEntity == null) {
                return false;
            }
            if (!isPropertySubset(candidateEntity.properties(), baseEntity.properties())) {
                return false;
            }
        }

        Map<String, OntologyRelationType> baseRelations = indexByIdentity(
            base.relationTypes(),
            OntologyRelationType::identityId
        );
        for (OntologyRelationType candidateRelation : candidate.relationTypes()) {
            OntologyRelationType baseRelation = baseRelations.get(candidateRelation.identityId());
            if (baseRelation == null) {
                return false;
            }
            if (!isPropertySubset(candidateRelation.properties(), baseRelation.properties())) {
                return false;
            }
            if (!isPatternSubset(candidateRelation.patterns(), baseRelation.patterns())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Detects contradictions between a candidate ontology and a base ontology.
     *
     * <p>A contradiction occurs when an element with the same identity has a
     * property with the same identity but a different {@link OntologyPropertyType}
     * in the two ontologies. Changes to entity or relation descriptions are not
     * treated as contradictions.
     *
     * @param candidate the candidate ontology
     * @param base      the base ontology
     * @return a list of human-readable contradiction descriptions
     */
    public static List<String> detectContradictions(Ontology candidate, Ontology base) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(base, "base");

        List<String> contradictions = new ArrayList<>();

        Map<String, OntologyEntityType> baseEntities = indexByIdentity(
            base.entityTypes(),
            OntologyEntityType::identityId
        );
        for (OntologyEntityType candidateEntity : candidate.entityTypes()) {
            OntologyEntityType baseEntity = baseEntities.get(candidateEntity.identityId());
            if (baseEntity != null) {
                contradictions.addAll(
                    detectPropertyContradictions(
                        "entity",
                        candidateEntity.identityId(),
                        candidateEntity.properties(),
                        baseEntity.properties()
                    )
                );
            }
        }

        Map<String, OntologyRelationType> baseRelations = indexByIdentity(
            base.relationTypes(),
            OntologyRelationType::identityId
        );
        for (OntologyRelationType candidateRelation : candidate.relationTypes()) {
            OntologyRelationType baseRelation = baseRelations.get(candidateRelation.identityId());
            if (baseRelation != null) {
                contradictions.addAll(
                    detectPropertyContradictions(
                        "relation",
                        candidateRelation.identityId(),
                        candidateRelation.properties(),
                        baseRelation.properties()
                    )
                );
            }
        }

        return List.copyOf(contradictions);
    }

    /**
     * Synthesizes a candidate ontology from a base ontology and a draft.
     *
     * <ul>
     *   <li>{@link SynthesizeMode#REGISTER_INCREMENTAL}: the draft is merged into
     *       the base; elements not listed in the draft are retained.</li>
     *   <li>{@link SynthesizeMode#EVOLUTION_REPLACE}: the draft is returned as the
     *       complete replacement candidate.</li>
     * </ul>
     *
     * @param base  the base ontology
     * @param draft the draft ontology
     * @param mode  the synthesis mode
     * @return the synthesized candidate
     */
    public static Ontology synthesizeCandidate(Ontology base, Ontology draft, SynthesizeMode mode) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(mode, "mode");

        return switch (mode) {
            case REGISTER_INCREMENTAL -> synthesizeRegisterIncremental(base, draft);
            case EVOLUTION_REPLACE -> draft;
        };
    }

    private static Ontology synthesizeRegisterIncremental(Ontology base, Ontology draft) {
        List<String> contradictions = detectContradictions(draft, base);
        if (!contradictions.isEmpty()) {
            throw new OntologyContradictionException(
                "REGISTER_INCREMENTAL contradictions: " + String.join("; ", contradictions)
            );
        }

        Map<String, OntologyEntityType> baseEntities = indexByIdentity(
            base.entityTypes(),
            OntologyEntityType::identityId
        );
        for (OntologyEntityType draftEntity : draft.entityTypes()) {
            OntologyEntityType baseEntity = baseEntities.get(draftEntity.identityId());
            if (baseEntity != null && !isPropertySubset(draftEntity.properties(), baseEntity.properties())) {
                throw new OntologyModificationNotAllowedException(
                    "REGISTER_INCREMENTAL cannot add or retype properties on existing entity '"
                        + draftEntity.identityId() + "'"
                );
            }
        }

        Map<String, OntologyRelationType> baseRelations = indexByIdentity(
            base.relationTypes(),
            OntologyRelationType::identityId
        );
        for (OntologyRelationType draftRelation : draft.relationTypes()) {
            OntologyRelationType baseRelation = baseRelations.get(draftRelation.identityId());
            if (baseRelation != null) {
                if (!isPropertySubset(draftRelation.properties(), baseRelation.properties())) {
                    throw new OntologyModificationNotAllowedException(
                        "REGISTER_INCREMENTAL cannot add or retype properties on existing relation '"
                            + draftRelation.identityId() + "'"
                    );
                }
                if (!isPatternSubset(draftRelation.patterns(), baseRelation.patterns())) {
                    throw new OntologyModificationNotAllowedException(
                        "REGISTER_INCREMENTAL cannot add patterns to existing relation '"
                            + draftRelation.identityId() + "'"
                    );
                }
            }
        }

        return base.merge(draft);
    }

    /**
     * Computes the canonical SHA-256 hash of a synthesized candidate.
     *
     * <p>The hash is computed over the canonical tuple
     * {@code (domain="candidate", v=1, fields=[("mode", mode), ("candidate", canonical(candidate))])}
     * using the framed tuple encoding: each tuple field is encoded as
     * {@code name\0<len>:<utf8-bytes>} where {@code <len>} is the decimal UTF-8
     * byte length of the value. The candidate value is the canonical JSON produced
     * by {@link OntologyJsonCodec#canonicalJson(Ontology)}.
     *
     * @param mode      the synthesis mode
     * @param candidate the candidate ontology
     * @return a hex-encoded SHA-256 digest
     */
    public static String candidateHash(SynthesizeMode mode, Ontology candidate) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(candidate, "candidate");

        byte[] innerFields = concat(
            encodeTupleField("mode", mode.name().getBytes(StandardCharsets.UTF_8)),
            new byte[] {0},
            encodeTupleField("candidate", OntologyJsonCodec.canonicalJson(candidate).getBytes(StandardCharsets.UTF_8))
        );

        byte[] tuple = concat(
            encodeTupleField("domain", "candidate".getBytes(StandardCharsets.UTF_8)),
            new byte[] {0},
            encodeTupleField("v", "1".getBytes(StandardCharsets.UTF_8)),
            new byte[] {0},
            encodeTupleField("fields", innerFields)
        );

        byte[] hash = sha256(tuple);
        return bytesToHex(hash);
    }

    /**
     * Computes the deletion manifest between two ontology snapshots.
     *
     * <p>The manifest uses the fully expanded form: when an owner (entity or
     * relation) is deleted, all of its properties and relation patterns are
     * listed individually. When an owner is retained, only the removed
     * properties and patterns are listed.
     *
     * @param before the earlier ontology
     * @param after  the later ontology
     * @return a canonically ordered deletion manifest
     */
    public static DeletionManifest diff(Ontology before, Ontology after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        Map<String, OntologyEntityType> afterEntities = indexByIdentity(
            after.entityTypes(),
            OntologyEntityType::identityId
        );
        Map<String, OntologyRelationType> afterRelations = indexByIdentity(
            after.relationTypes(),
            OntologyRelationType::identityId
        );

        List<DeletionEntry> deletions = new ArrayList<>();

        for (OntologyEntityType beforeEntity : before.entityTypes()) {
            OntologyEntityType afterEntity = afterEntities.get(beforeEntity.identityId());
            if (afterEntity == null) {
                deletions.add(new DeletionEntry.Entity(beforeEntity.identityId()));
                for (OntologyProperty property : beforeEntity.properties()) {
                    deletions.add(
                        new DeletionEntry.Property(
                            DeletionEntry.OwnerKind.ENTITY,
                            beforeEntity.identityId(),
                            property.identityId()
                        )
                    );
                }
            } else {
                Set<String> afterPropertyIds = afterEntity.properties().stream()
                    .map(OntologyProperty::identityId)
                    .collect(Collectors.toSet());
                for (OntologyProperty property : beforeEntity.properties()) {
                    if (!afterPropertyIds.contains(property.identityId())) {
                        deletions.add(
                            new DeletionEntry.Property(
                                DeletionEntry.OwnerKind.ENTITY,
                                beforeEntity.identityId(),
                                property.identityId()
                            )
                        );
                    }
                }
            }
        }

        for (OntologyRelationType beforeRelation : before.relationTypes()) {
            OntologyRelationType afterRelation = afterRelations.get(beforeRelation.identityId());
            if (afterRelation == null) {
                deletions.add(new DeletionEntry.Relation(beforeRelation.identityId()));
                for (OntologyProperty property : beforeRelation.properties()) {
                    deletions.add(
                        new DeletionEntry.Property(
                            DeletionEntry.OwnerKind.RELATION,
                            beforeRelation.identityId(),
                            property.identityId()
                        )
                    );
                }
                for (OntologyPattern pattern : beforeRelation.patterns()) {
                    deletions.add(
                        new DeletionEntry.Pattern(
                            beforeRelation.identityId(),
                            pattern.sourceLabel(),
                            pattern.targetLabel()
                        )
                    );
                }
            } else {
                Set<String> afterPropertyIds = afterRelation.properties().stream()
                    .map(OntologyProperty::identityId)
                    .collect(Collectors.toSet());
                for (OntologyProperty property : beforeRelation.properties()) {
                    if (!afterPropertyIds.contains(property.identityId())) {
                        deletions.add(
                            new DeletionEntry.Property(
                                DeletionEntry.OwnerKind.RELATION,
                                beforeRelation.identityId(),
                                property.identityId()
                            )
                        );
                    }
                }

                Set<String> afterPatternIds = afterRelation.patterns().stream()
                    .map(OntologyPattern::identityId)
                    .collect(Collectors.toSet());
                for (OntologyPattern pattern : beforeRelation.patterns()) {
                    if (!afterPatternIds.contains(pattern.identityId())) {
                        deletions.add(
                            new DeletionEntry.Pattern(
                                beforeRelation.identityId(),
                                pattern.sourceLabel(),
                                pattern.targetLabel()
                            )
                        );
                    }
                }
            }
        }

        return new DeletionManifest(deletions);
    }

    /**
     * Returns true if the relation type has a pattern that matches the given
     * source and target identities.
     *
     * <p>Matching tolerates both directions, so a pattern
     * {@code (A, B)} matches both {@code (A, B)} and {@code (B, A)}.
     *
     * @param relationType     the relation type to inspect
     * @param sourceIdentityId the source entity identity
     * @param targetIdentityId the target entity identity
     * @return true if a matching pattern exists
     */
    public static boolean relationTypeMatchesPatterns(
        OntologyRelationType relationType,
        String sourceIdentityId,
        String targetIdentityId
    ) {
        Objects.requireNonNull(relationType, "relationType");
        Objects.requireNonNull(sourceIdentityId, "sourceIdentityId");
        Objects.requireNonNull(targetIdentityId, "targetIdentityId");

        String source = Identity.identityId(sourceIdentityId);
        String target = Identity.identityId(targetIdentityId);
        for (OntologyPattern pattern : relationType.patterns()) {
            String patternSource = Identity.identityId(pattern.sourceLabel());
            String patternTarget = Identity.identityId(pattern.targetLabel());
            boolean forward = patternSource.equals(source) && patternTarget.equals(target);
            boolean reverse = patternSource.equals(target) && patternTarget.equals(source);
            if (forward || reverse) {
                return true;
            }
        }
        return false;
    }

    private static byte[] encodeTupleField(String name, byte[] value) {
        String header = name + "\0" + value.length + ":";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        return concat(headerBytes, value);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static <T> Map<String, T> indexByIdentity(List<T> items, Function<T, String> identityExtractor) {
        Map<String, T> map = new java.util.LinkedHashMap<>(items.size());
        for (T item : items) {
            map.put(identityExtractor.apply(item), item);
        }
        return map;
    }

    private static boolean isPropertySubset(List<OntologyProperty> candidate, List<OntologyProperty> base) {
        Map<String, OntologyProperty> baseById = indexByIdentity(base, OntologyProperty::identityId);
        for (OntologyProperty candidateProperty : candidate) {
            OntologyProperty baseProperty = baseById.get(candidateProperty.identityId());
            if (baseProperty == null || baseProperty.type() != candidateProperty.type()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPatternSubset(List<OntologyPattern> candidate, List<OntologyPattern> base) {
        Set<String> baseIds = base.stream().map(OntologyPattern::identityId).collect(Collectors.toSet());
        for (OntologyPattern candidatePattern : candidate) {
            if (!baseIds.contains(candidatePattern.identityId())) {
                return false;
            }
        }
        return true;
    }

    private static List<String> detectPropertyContradictions(
        String ownerKind,
        String ownerIdentityId,
        List<OntologyProperty> candidate,
        List<OntologyProperty> base
    ) {
        Map<String, OntologyProperty> baseById = indexByIdentity(base, OntologyProperty::identityId);
        List<String> contradictions = new ArrayList<>();
        for (OntologyProperty candidateProperty : candidate) {
            OntologyProperty baseProperty = baseById.get(candidateProperty.identityId());
            if (baseProperty != null && baseProperty.type() != candidateProperty.type()) {
                contradictions.add(
                    ownerKind + " '" + ownerIdentityId + "' property '" + candidateProperty.identityId()
                        + "' changed type from " + baseProperty.type() + " to " + candidateProperty.type()
                );
            }
        }
        return contradictions;
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available", exception);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
