package io.github.lightrag.indexing;

import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import io.github.lightrag.types.Relation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GraphAssembler {
    private static final Logger log = LoggerFactory.getLogger(GraphAssembler.class);

    public Graph assemble(List<ChunkExtraction> extractions) {
        var batch = List.copyOf(Objects.requireNonNull(extractions, "extractions"));
        var mergeGuard = MergeGuard.from(batch);
        var entitiesById = new LinkedHashMap<String, MutableEntity>();
        var entityIdByMergeKey = new LinkedHashMap<String, String>();

        for (var extraction : batch) {
            var chunkExtraction = Objects.requireNonNull(extraction, "extraction");
            for (var entity : chunkExtraction.extraction().entities()) {
                mergeEntity(chunkExtraction.chunkId(), entity, entitiesById, entityIdByMergeKey, mergeGuard);
            }
        }

        var relationsById = new LinkedHashMap<String, MutableRelation>();
        var relationIdByMergeKey = new LinkedHashMap<String, String>();
        int skippedSelfLoopRelations = 0;
        for (var extraction : batch) {
            var chunkExtraction = Objects.requireNonNull(extraction, "extraction");
            for (var relation : chunkExtraction.extraction().relations()) {
                if (!mergeRelation(
                    chunkExtraction.chunkId(),
                    relation,
                    entitiesById,
                    entityIdByMergeKey,
                    relationsById,
                    relationIdByMergeKey
                )) {
                    skippedSelfLoopRelations++;
                }
            }
        }
        if (skippedSelfLoopRelations > 0) {
            log.warn(
                "graph_assembly_event=skip_self_loop skippedCount={} chunkExtractionCount={}",
                skippedSelfLoopRelations,
                batch.size()
            );
        }

        return new Graph(
            entitiesById.values().stream().map(MutableEntity::toEntity).toList(),
            relationsById.values().stream().map(MutableRelation::toRelation).toList()
        );
    }

    private static void mergeEntity(
        String chunkId,
        ExtractedEntity extractedEntity,
        Map<String, MutableEntity> entitiesById,
        Map<String, String> entityIdByMergeKey,
        MergeGuard mergeGuard
    ) {
        var sanitizedEntity = mergeGuard.sanitize(extractedEntity);
        var candidateKeys = entityMergeKeys(sanitizedEntity);
        var matchedIds = new LinkedHashSet<String>();
        for (var key : candidateKeys) {
            var matchedId = entityIdByMergeKey.get(key);
            if (matchedId != null && !mergeGuard.blocksMerge(sanitizedEntity, entitiesById.get(matchedId))) {
                matchedIds.add(matchedId);
            }
        }

        MutableEntity entity;
        if (matchedIds.isEmpty()) {
            entity = MutableEntity.create(sanitizedEntity);
            entitiesById.put(entity.id, entity);
        } else {
            var iterator = matchedIds.iterator();
            entity = entitiesById.get(iterator.next());
            while (iterator.hasNext()) {
                var duplicate = entitiesById.remove(iterator.next());
                if (duplicate != null) {
                    entity.mergeFrom(duplicate);
                }
            }
            entity.mergeFrom(sanitizedEntity);
        }

        entity.addSourceChunkId(chunkId);
        entity.registerMergeKeys(candidateKeys);
        for (var key : entity.mergeKeys()) {
            entityIdByMergeKey.put(key, entity.id);
        }
    }

    private static boolean mergeRelation(
        String chunkId,
        ExtractedRelation extractedRelation,
        Map<String, MutableEntity> entitiesById,
        Map<String, String> entityIdByMergeKey,
        Map<String, MutableRelation> relationsById,
        Map<String, String> relationIdByMergeKey
    ) {
        var sourceEntity = ensureEntity(chunkId, extractedRelation.sourceEntityName(), entitiesById, entityIdByMergeKey);
        var targetEntity = ensureEntity(chunkId, extractedRelation.targetEntityName(), entitiesById, entityIdByMergeKey);
        // Drop self-loops after endpoint normalization so one bad extraction does not abort the batch.
        if (sourceEntity.id.equals(targetEntity.id)) {
            log.warn(
                "graph_assembly_event=self_loop_relation_skipped chunkId={} sourceEntity={} targetEntity={} keywords={}",
                chunkId,
                extractedRelation.sourceEntityName(),
                extractedRelation.targetEntityName(),
                extractedRelation.keywords()
            );
            return false;
        }
        var canonicalRef = RelationCanonicalizer.canonicalize(sourceEntity.id, targetEntity.id);
        var mergeKey = relationMergeKey(canonicalRef.srcId(), canonicalRef.tgtId());
        var relationId = relationIdByMergeKey.get(mergeKey);
        if (relationId == null) {
            relationId = canonicalRef.relationId();
        }
        var finalRelationId = relationId;
        var relation = relationsById.computeIfAbsent(
            finalRelationId,
            ignored -> MutableRelation.create(finalRelationId, canonicalRef.srcId(), canonicalRef.tgtId(), extractedRelation)
        );
        relation.mergeFrom(extractedRelation);
        relation.addSourceChunkId(chunkId);
        relationIdByMergeKey.put(mergeKey, finalRelationId);
        return true;
    }

    private static MutableEntity ensureEntity(
        String chunkId,
        String entityName,
        Map<String, MutableEntity> entitiesById,
        Map<String, String> entityIdByMergeKey
    ) {
        var normalizedName = normalizeKey(entityName);
        var entityId = entityIdByMergeKey.get(normalizedName);
        if (entityId != null) {
            var existing = entitiesById.get(entityId);
            existing.addSourceChunkId(chunkId);
            return existing;
        }

        var created = MutableEntity.create(new ExtractedEntity(entityName, "", "", List.of()));
        created.addSourceChunkId(chunkId);
        created.registerMergeKeys(Set.of(normalizedName));
        entitiesById.put(created.id, created);
        for (var key : created.mergeKeys()) {
            entityIdByMergeKey.put(key, created.id);
        }
        return created;
    }

    private static Set<String> entityMergeKeys(ExtractedEntity entity) {
        var keys = new LinkedHashSet<String>();
        keys.add(normalizeKey(entity.name()));
        for (var alias : entity.aliases()) {
            var normalizedAlias = normalizeOptionalKey(alias);
            if (normalizedAlias != null) {
                keys.add(normalizedAlias);
            }
        }
        return keys;
    }

    private static String normalizeKey(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptionalKey(String value) {
        if (value == null) {
            return null;
        }
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String relationMergeKey(String sourceEntityId, String targetEntityId) {
        return sourceEntityId + "\u0000" + targetEntityId;
    }

    public record ChunkExtraction(String chunkId, ExtractionResult extraction) {
        public ChunkExtraction {
            chunkId = Objects.requireNonNull(chunkId, "chunkId").strip();
            if (chunkId.isEmpty()) {
                throw new IllegalArgumentException("chunkId must not be blank");
            }
            extraction = Objects.requireNonNull(extraction, "extraction");
        }
    }

    public record Graph(List<Entity> entities, List<Relation> relations) {
        public Graph {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
        }
    }

    private record MergeGuard(Set<String> relationPairs) {
        private static MergeGuard from(List<ChunkExtraction> batch) {
            var pairs = new LinkedHashSet<String>();
            for (var extraction : batch) {
                for (var relation : extraction.extraction().relations()) {
                    var source = normalizeOptionalKey(relation.sourceEntityName());
                    var target = normalizeOptionalKey(relation.targetEntityName());
                    if (source == null || target == null || source.equals(target)) {
                        continue;
                    }
                    pairs.add(relationPairKey(source, target));
                }
            }
            return new MergeGuard(Set.copyOf(pairs));
        }

        private ExtractedEntity sanitize(ExtractedEntity entity) {
            var normalizedName = normalizeKey(entity.name());
            var aliases = entity.aliases().stream()
                .filter(alias -> {
                    var normalizedAlias = normalizeOptionalKey(alias);
                    return normalizedAlias != null && !isRelatedPair(normalizedName, normalizedAlias);
                })
                .toList();
            if (aliases.equals(entity.aliases())) {
                return entity;
            }
            return new ExtractedEntity(entity.name(), entity.type(), entity.description(), aliases);
        }

        private boolean blocksMerge(ExtractedEntity incoming, MutableEntity existing) {
            if (existing == null) {
                return false;
            }
            for (var incomingKey : entityMergeKeys(incoming)) {
                for (var existingKey : existing.mergeKeys()) {
                    if (!incomingKey.equals(existingKey) && isRelatedPair(incomingKey, existingKey)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isRelatedPair(String left, String right) {
            return relationPairs.contains(relationPairKey(left, right));
        }

        private static String relationPairKey(String left, String right) {
            return left.compareTo(right) <= 0 ? left + "\u0000" + right : right + "\u0000" + left;
        }
    }

    private static final class MutableEntity {
        private final String id;
        private final String name;
        private String type;
        private String description;
        private final LinkedHashMap<String, String> aliasesByKey;
        private final LinkedHashSet<String> sourceChunkIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> mergeKeys = new LinkedHashSet<>();

        private MutableEntity(String id, String name, String type, String description, LinkedHashMap<String, String> aliasesByKey) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.description = description;
            this.aliasesByKey = aliasesByKey;
        }

        private static MutableEntity create(ExtractedEntity entity) {
            var normalizedName = normalizeKey(entity.name());
            var mutable = new MutableEntity(
                normalizedName,
                entity.name(),
                entity.type(),
                entity.description(),
                new LinkedHashMap<>()
            );
            mutable.addAliases(entity.aliases());
            mutable.registerMergeKeys(entityMergeKeys(entity));
            return mutable;
        }

        private void mergeFrom(ExtractedEntity entity) {
            var normalizedIncomingName = normalizeKey(entity.name());
            if (!normalizedIncomingName.equals(normalizeKey(name))) {
                addAlias(entity.name());
            }
            if (type.isEmpty() && !entity.type().isEmpty()) {
                type = entity.type();
            }
            if (description.isEmpty() && !entity.description().isEmpty()) {
                description = entity.description();
            }
            addAliases(entity.aliases());
            registerMergeKeys(entityMergeKeys(entity));
        }

        private void mergeFrom(MutableEntity entity) {
            if (type.isEmpty() && !entity.type.isEmpty()) {
                type = entity.type;
            }
            if (description.isEmpty() && !entity.description.isEmpty()) {
                description = entity.description;
            }
            for (var alias : entity.aliasesByKey.values()) {
                addAlias(alias);
            }
            sourceChunkIds.addAll(entity.sourceChunkIds);
            mergeKeys.addAll(entity.mergeKeys);
        }

        private void addAliases(List<String> aliases) {
            for (var alias : aliases) {
                addAlias(alias);
            }
        }

        private void addAlias(String alias) {
            var normalizedAlias = normalizeOptionalKey(alias);
            if (normalizedAlias == null || normalizedAlias.equals(normalizeKey(name))) {
                return;
            }
            aliasesByKey.putIfAbsent(normalizedAlias, alias.strip());
        }

        private void addSourceChunkId(String chunkId) {
            sourceChunkIds.add(chunkId);
        }

        private void registerMergeKeys(Set<String> keys) {
            mergeKeys.addAll(keys);
        }

        private Set<String> mergeKeys() {
            return mergeKeys;
        }

        private Entity toEntity() {
            return new Entity(
                id,
                name,
                type,
                description,
                new ArrayList<>(aliasesByKey.values()),
                new ArrayList<>(sourceChunkIds)
            );
        }
    }

    private static final class MutableRelation {
        private final String id;
        private final String srcId;
        private final String tgtId;
        private String keywords;
        private String description;
        private double weight;
        private final LinkedHashSet<String> sourceChunkIds = new LinkedHashSet<>();

        private MutableRelation(
            String id,
            String srcId,
            String tgtId,
            String keywords,
            String description,
            double weight
        ) {
            this.id = id;
            this.srcId = srcId;
            this.tgtId = tgtId;
            this.keywords = keywords;
            this.description = description;
            this.weight = weight;
        }

        private static MutableRelation create(
            String id,
            String sourceEntityId,
            String targetEntityId,
            ExtractedRelation relation
        ) {
            return new MutableRelation(
                id,
                sourceEntityId,
                targetEntityId,
                canonicalKeywords(relation.keywords()),
                relation.description(),
                relation.weight()
            );
        }

        private void mergeFrom(ExtractedRelation relation) {
            keywords = mergeKeywords(keywords, relation.keywords());
            if (description.isEmpty() && !relation.description().isEmpty()) {
                description = relation.description();
            }
            weight = Math.max(weight, relation.weight());
        }

        private void addSourceChunkId(String chunkId) {
            sourceChunkIds.add(chunkId);
        }

        private Relation toRelation() {
            return new Relation(
                id,
                srcId,
                tgtId,
                keywords,
                description,
                weight,
                RelationCanonicalizer.joinValues(new ArrayList<>(sourceChunkIds)),
                ""
            );
        }
    }

    private static String canonicalKeywords(String value) {
        var normalized = normalizeKey(value);
        if (normalized.isEmpty()) {
            return normalized;
        }
        var keywords = new java.util.TreeSet<String>();
        for (var rawKeyword : normalized.split(",")) {
            var keyword = rawKeyword.strip().replaceAll("[\\s_-]+", "_");
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
        return String.join(", ", keywords);
    }

    private static String mergeKeywords(String current, String incoming) {
        var keywords = new LinkedHashSet<String>();
        addKeywords(keywords, current);
        addKeywords(keywords, incoming);
        return String.join(", ", keywords);
    }

    private static void addKeywords(LinkedHashSet<String> keywords, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (var rawKeyword : value.split(",")) {
            var keyword = rawKeyword.strip().replaceAll("[\\s_-]+", "_");
            if (!keyword.isEmpty()) {
                keywords.add(keyword);
            }
        }
    }
}
