package io.github.lightrag.ontology;

import io.github.lightrag.api.KgExtractionValidator;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Filters extracted entities and relations against an ontology.
 *
 * <p>Entities whose type is not declared in the ontology are dropped in
 * {@link ExtractionMode#STRICT} and coerced to the fallback {@code Other} in
 * {@link ExtractionMode#PREFER}. Relations are validated with the universal
 * predicate from the ontology plan: every relation keyword that hits a declared
 * relation type must satisfy that type's patterns (tolerating both directions).
 * A relation is rejected only when none of its matching pattern-bearing relation
 * types accepts the endpoint pair. STRICT drops non-conforming relations; PREFER
 * keeps evidence-backed relations and reports them through the unmatched
 * consumer; OPEN returns the extraction unchanged.
 */
public final class OntologyConformanceValidator implements KgExtractionValidator {

    private static final String FALLBACK_TYPE = "Other";

    private final Ontology ontology;
    private final Consumer<UnmatchedLabel> unmatchedConsumer;
    private final boolean dropOnUnknownKeyword;
    private final ExtractionMode mode;
    private final Set<String> entityTypeIds;
    private final Map<String, OntologyRelationType> relationTypesById;

    public OntologyConformanceValidator(Ontology ontology) {
        this(ontology, ignored -> {}, false, ExtractionMode.STRICT);
    }

    public OntologyConformanceValidator(Ontology ontology, boolean dropOnUnknownKeyword) {
        this(ontology, ignored -> {}, dropOnUnknownKeyword, ExtractionMode.STRICT);
    }

    public OntologyConformanceValidator(
        Ontology ontology,
        Consumer<UnmatchedLabel> unmatchedConsumer,
        boolean dropOnUnknownKeyword
    ) {
        this(ontology, unmatchedConsumer, dropOnUnknownKeyword, ExtractionMode.STRICT);
    }

    public OntologyConformanceValidator(
        Ontology ontology,
        Consumer<UnmatchedLabel> unmatchedConsumer,
        boolean dropOnUnknownKeyword,
        ExtractionMode mode
    ) {
        this.ontology = Objects.requireNonNull(ontology, "ontology");
        this.unmatchedConsumer = unmatchedConsumer == null ? ignored -> {} : unmatchedConsumer;
        this.dropOnUnknownKeyword = dropOnUnknownKeyword;
        this.mode = mode == null ? ExtractionMode.STRICT : mode;
        this.entityTypeIds = ontology.entityTypes().stream()
            .map(OntologyEntityType::identityId)
            .collect(Collectors.toUnmodifiableSet());
        this.relationTypesById = ontology.relationTypes().stream()
            .collect(Collectors.toUnmodifiableMap(
                OntologyRelationType::identityId,
                rel -> rel,
                (first, second) -> first
            ));
    }

    @Override
    public ExtractionResult validate(String chunkId, String chunkText, ExtractionResult extracted) {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(extracted, "extracted");
        if (mode == ExtractionMode.OPEN) {
            return extracted;
        }

        var validEntities = new ArrayList<ExtractedEntity>();
        var droppedEntityNames = new HashSet<String>();
        for (ExtractedEntity entity : extracted.entities()) {
            if (isKnownEntityType(entity.type())) {
                validEntities.add(entity);
                continue;
            }
            unmatchedConsumer.accept(new UnmatchedLabel(
                UnmatchedLabel.Kind.ENTITY,
                entity.type(),
                chunkId,
                UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE
            ));
            if (mode == ExtractionMode.PREFER) {
                validEntities.add(new ExtractedEntity(
                    entity.name(),
                    FALLBACK_TYPE,
                    entity.description(),
                    entity.aliases()
                ));
            } else {
                droppedEntityNames.add(entity.name());
            }
        }

        var validRelations = new ArrayList<ExtractedRelation>();
        for (ExtractedRelation relation : extracted.relations()) {
            if (droppedEntityNames.contains(relation.sourceEntityName())
                || droppedEntityNames.contains(relation.targetEntityName())) {
                unmatchedConsumer.accept(new UnmatchedLabel(
                    UnmatchedLabel.Kind.RELATION,
                    relation.keywords(),
                    chunkId,
                    UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE
                ));
                continue;
            }

            var sourceType = findEntityType(relation.sourceEntityName(), validEntities);
            var targetType = findEntityType(relation.targetEntityName(), validEntities);

            var validation = validateRelation(relation, sourceType, targetType, chunkId);
            if (validation.isValid()) {
                validRelations.add(relation);
            }
        }

        return new ExtractionResult(
            List.copyOf(validEntities),
            List.copyOf(validRelations),
            extracted.warnings()
        );
    }

    private boolean isKnownEntityType(String type) {
        String normalized = Identity.normalizeWhitespace(type);
        if (normalized == null) {
            return false;
        }
        String typeId = Identity.identityId(normalized);
        return entityTypeIds.contains(typeId) || FALLBACK_TYPE.equalsIgnoreCase(normalized);
    }

    private String findEntityType(String entityName, List<ExtractedEntity> entities) {
        for (ExtractedEntity entity : entities) {
            if (entity.name().equals(entityName)) {
                String normalized = Identity.normalizeWhitespace(entity.type());
                return normalized == null ? null : Identity.identityId(normalized);
            }
        }
        return null;
    }

    private RelationValidation validateRelation(
        ExtractedRelation relation,
        String sourceTypeId,
        String targetTypeId,
        String chunkId
    ) {
        List<String> keywords = parseKeywords(relation.keywords());
        if (keywords.isEmpty()) {
            unmatchedConsumer.accept(new UnmatchedLabel(
                UnmatchedLabel.Kind.RELATION,
                relation.keywords(),
                chunkId,
                UnmatchedLabel.Reason.UNKNOWN_RELATION_TYPE
            ));
            return RelationValidation.invalid();
        }

        if (sourceTypeId == null || targetTypeId == null) {
            unmatchedConsumer.accept(new UnmatchedLabel(
                UnmatchedLabel.Kind.RELATION,
                relation.keywords(),
                chunkId,
                UnmatchedLabel.Reason.DANGLING_ENDPOINT
            ));
            return RelationValidation.invalid();
        }

        Set<String> matchedTypeIds = new HashSet<>();
        boolean hasUnknownKeyword = false;
        for (String keyword : keywords) {
            String keywordId = Identity.identityId(keyword);
            OntologyRelationType relationType = relationTypesById.get(keywordId);
            if (relationType == null) {
                hasUnknownKeyword = true;
                unmatchedConsumer.accept(new UnmatchedLabel(
                    UnmatchedLabel.Kind.RELATION_KEYWORD,
                    keyword,
                    chunkId,
                    UnmatchedLabel.Reason.UNKNOWN_RELATION_KEYWORD
                ));
            } else {
                matchedTypeIds.add(keywordId);
            }
        }

        if (matchedTypeIds.isEmpty()) {
            unmatchedConsumer.accept(new UnmatchedLabel(
                UnmatchedLabel.Kind.RELATION,
                relation.keywords(),
                chunkId,
                UnmatchedLabel.Reason.UNKNOWN_RELATION_TYPE
            ));
            return mode == ExtractionMode.PREFER
                ? RelationValidation.valid()
                : RelationValidation.invalid();
        }

        if (hasUnknownKeyword && dropOnUnknownKeyword) {
            return RelationValidation.invalid();
        }

        boolean patternConstrained = false;
        boolean patternMatched = false;
        for (String typeId : matchedTypeIds) {
            OntologyRelationType relationType = relationTypesById.get(typeId);
            if (relationType == null || relationType.patterns().isEmpty()) {
                continue;
            }
            patternConstrained = true;
            if (OntologyGovernance.relationTypeMatchesPatterns(relationType, sourceTypeId, targetTypeId)) {
                patternMatched = true;
                break;
            }
        }
        if (patternConstrained && !patternMatched) {
            unmatchedConsumer.accept(new UnmatchedLabel(
                UnmatchedLabel.Kind.RELATION,
                relation.keywords(),
                chunkId,
                UnmatchedLabel.Reason.PATTERN_MISMATCH
            ));
            if (mode != ExtractionMode.PREFER) {
                return RelationValidation.invalid();
            }
        }

        return RelationValidation.valid();
    }

    private static List<String> parseKeywords(String keywords) {
        if (Identity.normalizeWhitespace(keywords) == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : keywords.split(",")) {
            String normalized = Identity.normalizeWhitespace(part);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private record RelationValidation(boolean isValid) {
        static RelationValidation valid() {
            return new RelationValidation(true);
        }

        static RelationValidation invalid() {
            return new RelationValidation(false);
        }
    }
}
