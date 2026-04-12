package io.github.lightrag.indexing;

import io.github.lightrag.api.CreateEntityRequest;
import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.api.EditEntityRequest;
import io.github.lightrag.api.EditRelationRequest;
import io.github.lightrag.api.GraphEntity;
import io.github.lightrag.api.GraphRelation;
import io.github.lightrag.api.MergeEntitiesRequest;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class GraphManagementPipeline {
    private final AtomicStorageProvider storageProvider;
    private final IndexingPipeline indexingPipeline;
    private final Path snapshotPath;

    public GraphManagementPipeline(
        AtomicStorageProvider storageProvider,
        IndexingPipeline indexingPipeline,
        Path snapshotPath
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.indexingPipeline = Objects.requireNonNull(indexingPipeline, "indexingPipeline");
        this.snapshotPath = snapshotPath;
    }

    public GraphEntity createEntity(CreateEntityRequest request) {
        var createRequest = Objects.requireNonNull(request, "request");
        var entityRecord = storageProvider.writeAtomically(storage -> {
            validateEntityIdentityNamespace(storage.graphStore().allEntities(), null, createRequest.name(), createRequest.aliases());

            var created = new GraphStore.EntityRecord(
                entityId(createRequest.name()),
                createRequest.name(),
                createRequest.type(),
                createRequest.description(),
                createRequest.aliases(),
                List.of()
            );
            storage.graphStore().saveEntity(created);
            saveEntityVectors(storage.vectorStore(), List.of(toEntity(created)));
            return created;
        });
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphEntity(entityRecord);
    }

    public GraphRelation createRelation(CreateRelationRequest request) {
        var createRequest = Objects.requireNonNull(request, "request");
        var relationRecord = storageProvider.writeAtomically(storage -> {
            var sourceEntity = resolveEntity(storage.graphStore().allEntities(), createRequest.sourceEntityName(), "sourceEntityName");
            var targetEntity = resolveEntity(storage.graphStore().allEntities(), createRequest.targetEntityName(), "targetEntityName");
            var created = new GraphStore.RelationRecord(
                relationId(sourceEntity.id(), createRequest.relationType(), targetEntity.id()),
                sourceEntity.id(),
                targetEntity.id(),
                createRequest.relationType(),
                createRequest.description(),
                createRequest.weight(),
                List.of()
            );
            validateCreateRelation(storage.graphStore().allRelations(), created);
            storage.graphStore().saveRelation(created);
            saveRelationVectors(storage.vectorStore(), List.of(toRelation(created)));
            return created;
        });
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphRelation(relationRecord);
    }

    public GraphEntity editEntity(EditEntityRequest request) {
        var editRequest = Objects.requireNonNull(request, "request");
        var snapshot = StorageSnapshots.capture(storageProvider);
        var existing = resolveEntity(snapshot.entities(), editRequest.entityName(), "entityName");
        var nameUpdated = editRequest.newName() != null && !existing.name().equals(editRequest.newName());
        var renamed = editRequest.newName() != null && !normalizeKey(existing.name()).equals(normalizeKey(editRequest.newName()));
        var effectiveName = editRequest.newName() == null ? existing.name() : editRequest.newName();
        var aliases = editRequest.aliases() == null
            ? (nameUpdated ? normalizeAliasesForName(effectiveName, existing.aliases()) : existing.aliases())
            : normalizeAliasesForName(effectiveName, editRequest.aliases());
        if (nameUpdated) {
            aliases = appendAliasIfMissing(aliases, existing.name(), effectiveName);
        }

        validateEntityIdentityNamespace(snapshot.entities(), existing.id(), effectiveName, aliases);

        var updatedEntity = new GraphStore.EntityRecord(
            entityId(effectiveName),
            effectiveName,
            editRequest.type() == null ? existing.type() : editRequest.type(),
            editRequest.description() == null ? existing.description() : editRequest.description(),
            aliases,
            existing.sourceChunkIds()
        );

        var updatedRelations = renamed
            ? migrateRelations(snapshot.relations(), existing.id(), updatedEntity.id())
            : snapshot.relations();
        validateRelationIdsUnique(updatedRelations);
        var updatedVectors = new LinkedHashMap<>(snapshot.vectors());
        updatedVectors.put(
            StorageSnapshots.ENTITY_NAMESPACE,
            replaceNamespaceVectors(
                snapshot.vectors().getOrDefault(StorageSnapshots.ENTITY_NAMESPACE, List.of()),
                List.of(existing.id()),
                indexingPipeline.entityVectors(List.of(toEntity(updatedEntity)))
            )
        );
        if (renamed) {
            var previousRelationIds = snapshot.relations().stream()
                .filter(relation -> relation.sourceEntityId().equals(existing.id()) || relation.targetEntityId().equals(existing.id()))
                .map(GraphStore.RelationRecord::id)
                .toList();
            var replacementRelations = updatedRelations.stream()
                .filter(relation -> relation.sourceEntityId().equals(updatedEntity.id()) || relation.targetEntityId().equals(updatedEntity.id()))
                .map(GraphManagementPipeline::toRelation)
                .toList();
            updatedVectors.put(
                StorageSnapshots.RELATION_NAMESPACE,
                replaceNamespaceVectors(
                    snapshot.vectors().getOrDefault(StorageSnapshots.RELATION_NAMESPACE, List.of()),
                    previousRelationIds,
                    indexingPipeline.relationVectors(replacementRelations)
                )
            );
        }

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            replaceEntity(snapshot.entities(), existing.id(), updatedEntity),
            updatedRelations,
            Map.copyOf(updatedVectors),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphEntity(updatedEntity);
    }

    public GraphRelation editRelation(EditRelationRequest request) {
        var editRequest = Objects.requireNonNull(request, "request");
        var snapshot = StorageSnapshots.capture(storageProvider);
        var sourceEntity = resolveEntity(snapshot.entities(), editRequest.sourceEntityName(), "sourceEntityName");
        var targetEntity = resolveEntity(snapshot.entities(), editRequest.targetEntityName(), "targetEntityName");
        var currentRelationId = relationId(sourceEntity.id(), editRequest.currentRelationType(), targetEntity.id());
        var existing = snapshot.relations().stream()
            .filter(relation -> relation.id().equals(currentRelationId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("relation does not exist: " + currentRelationId));

        var updatedType = editRequest.newRelationType() == null ? existing.type() : editRequest.newRelationType();
        var updatedRelation = new GraphStore.RelationRecord(
            relationId(existing.sourceEntityId(), updatedType, existing.targetEntityId()),
            existing.sourceEntityId(),
            existing.targetEntityId(),
            updatedType,
            editRequest.description() == null ? existing.description() : editRequest.description(),
            editRequest.weight() == null ? existing.weight() : editRequest.weight(),
            existing.sourceChunkIds()
        );
        if (!updatedRelation.id().equals(existing.id())
            && snapshot.relations().stream().anyMatch(relation -> relation.id().equals(updatedRelation.id()))) {
            throw new IllegalArgumentException("relation already exists: " + updatedRelation.id());
        }

        var updatedVectors = new LinkedHashMap<>(snapshot.vectors());
        updatedVectors.put(
            StorageSnapshots.RELATION_NAMESPACE,
            replaceNamespaceVectors(
                snapshot.vectors().getOrDefault(StorageSnapshots.RELATION_NAMESPACE, List.of()),
                List.of(existing.id()),
                indexingPipeline.relationVectors(List.of(toRelation(updatedRelation)))
            )
        );

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities(),
            replaceRelation(snapshot.relations(), existing.id(), updatedRelation),
            Map.copyOf(updatedVectors),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphRelation(updatedRelation);
    }

    public GraphEntity mergeEntities(MergeEntitiesRequest request) {
        var mergeRequest = Objects.requireNonNull(request, "request");
        var snapshot = StorageSnapshots.capture(storageProvider);
        var target = resolveEntity(snapshot.entities(), mergeRequest.targetEntityName(), "targetEntityName");
        var sources = resolveMergeSources(snapshot.entities(), target.id(), mergeRequest.sourceEntityNames());
        var sourceIds = sources.stream()
            .map(GraphStore.EntityRecord::id)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        var mergedTarget = new GraphStore.EntityRecord(
            target.id(),
            target.name(),
            mergeRequest.targetType() == null ? target.type() : mergeRequest.targetType(),
            mergeRequest.targetDescription() == null
                ? mergeDescriptions(concatDescriptions(target.description(), sources))
                : mergeRequest.targetDescription(),
            mergeRequest.targetAliases() == null
                ? mergedAliases(target, sources)
                : normalizeAliasesForName(target.name(), mergeRequest.targetAliases()),
            mergeSourceChunkIds(target, sources)
        );
        validateEntityIdentityNamespace(
            snapshot.entities().stream()
                .filter(entity -> !sourceIds.contains(entity.id()))
                .toList(),
            target.id(),
            mergedTarget.name(),
            mergedTarget.aliases()
        );

        var updatedRelations = mergeRelations(snapshot.relations(), sourceIds, target.id());
        var updatedEntities = mergeEntities(snapshot.entities(), target.id(), sourceIds, mergedTarget);

        var updatedVectors = new LinkedHashMap<>(snapshot.vectors());
        updatedVectors.put(
            StorageSnapshots.ENTITY_NAMESPACE,
            indexingPipeline.entityVectors(updatedEntities.stream().map(GraphManagementPipeline::toEntity).toList())
        );
        updatedVectors.put(
            StorageSnapshots.RELATION_NAMESPACE,
            indexingPipeline.relationVectors(updatedRelations.stream().map(GraphManagementPipeline::toRelation).toList())
        );

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            updatedEntities,
            updatedRelations,
            Map.copyOf(updatedVectors),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphEntity(mergedTarget);
    }

    private static void validateCreateRelation(
        List<GraphStore.RelationRecord> relations,
        GraphStore.RelationRecord relationRecord
    ) {
        if (relations.stream().anyMatch(existing -> existing.id().equals(relationRecord.id()))) {
            throw new IllegalArgumentException("relation already exists: " + relationRecord.id());
        }
    }

    private static void validateRelationIdsUnique(List<GraphStore.RelationRecord> relations) {
        var relationIds = new LinkedHashSet<String>();
        for (var relation : relations) {
            if (!relationIds.add(relation.id())) {
                throw new IllegalArgumentException("relation already exists: " + relation.id());
            }
        }
    }

    private static void validateEntityIdentityNamespace(
        List<GraphStore.EntityRecord> entities,
        String currentEntityId,
        String effectiveName,
        List<String> aliases
    ) {
        var requestedNameKey = normalizeKey(effectiveName);
        var requestedAliasKeys = aliasKeys(aliases);
        for (var entity : entities) {
            if (entity.id().equals(currentEntityId)) {
                continue;
            }
            var existingNameKey = normalizeKey(entity.name());
            if (requestedNameKey.equals(existingNameKey) || requestedAliasKeys.contains(existingNameKey)) {
                throw new IllegalArgumentException("entity name or alias already exists: " + entity.name());
            }
            for (var alias : entity.aliases()) {
                var aliasKey = normalizeOptionalKey(alias);
                if (aliasKey != null && (requestedNameKey.equals(aliasKey) || requestedAliasKeys.contains(aliasKey))) {
                    throw new IllegalArgumentException("entity name or alias already exists: " + alias.strip());
                }
            }
        }
    }

    private static GraphStore.EntityRecord resolveEntity(
        List<GraphStore.EntityRecord> entities,
        String entityName,
        String fieldName
    ) {
        var normalized = normalizeKey(entityName);
        var exactNameMatches = entities.stream()
            .filter(entity -> normalizeKey(entity.name()).equals(normalized))
            .toList();
        if (exactNameMatches.size() == 1) {
            return exactNameMatches.get(0);
        }
        if (exactNameMatches.size() > 1) {
            throw new IllegalArgumentException(fieldName + " resolves to multiple entities");
        }

        var aliasMatches = entities.stream()
            .filter(entity -> entity.aliases().stream()
                .map(GraphManagementPipeline::normalizeOptionalKey)
                .anyMatch(normalized::equals))
            .toList();
        if (aliasMatches.size() == 1) {
            return aliasMatches.get(0);
        }
        if (aliasMatches.size() > 1) {
            throw new IllegalArgumentException(fieldName + " resolves ambiguously via alias");
        }
        throw new IllegalArgumentException(fieldName + " does not match an existing entity");
    }

    private static List<String> aliasKeys(List<String> aliases) {
        var keys = new LinkedHashSet<String>();
        for (var alias : aliases) {
            var normalizedAlias = normalizeOptionalKey(alias);
            if (normalizedAlias != null) {
                keys.add(normalizedAlias);
            }
        }
        return List.copyOf(keys);
    }

    private static List<String> normalizeAliasesForName(String entityName, List<String> aliases) {
        var normalizedName = normalizeKey(entityName);
        var normalizedAliases = new LinkedHashMap<String, String>();
        for (var alias : aliases) {
            var normalizedAlias = normalizeOptionalKey(alias);
            if (normalizedAlias == null || normalizedAlias.equals(normalizedName)) {
                continue;
            }
            normalizedAliases.putIfAbsent(normalizedAlias, alias.strip());
        }
        return List.copyOf(normalizedAliases.values());
    }

    private static List<String> appendAliasIfMissing(List<String> aliases, String alias, String entityName) {
        var normalizedAlias = normalizeOptionalKey(alias);
        if (normalizedAlias == null || normalizedAlias.equals(normalizeKey(entityName))) {
            return aliases;
        }
        var normalizedAliases = new LinkedHashMap<String, String>();
        for (var existing : aliases) {
            normalizedAliases.put(normalizeKey(existing), existing);
        }
        normalizedAliases.putIfAbsent(normalizedAlias, alias.strip());
        return List.copyOf(normalizedAliases.values());
    }

    private static List<GraphStore.EntityRecord> resolveMergeSources(
        List<GraphStore.EntityRecord> entities,
        String targetEntityId,
        List<String> sourceEntityNames
    ) {
        var values = new ArrayList<GraphStore.EntityRecord>();
        var sourceIds = new LinkedHashSet<String>();
        for (var sourceEntityName : sourceEntityNames) {
            var source = resolveEntity(entities, sourceEntityName, "sourceEntityName");
            if (source.id().equals(targetEntityId)) {
                throw new IllegalArgumentException("target entity must not be included in sourceEntityNames");
            }
            if (!sourceIds.add(source.id())) {
                throw new IllegalArgumentException("sourceEntityNames resolves to duplicate entities");
            }
            values.add(source);
        }
        return List.copyOf(values);
    }

    private static List<String> concatDescriptions(String targetDescription, List<GraphStore.EntityRecord> sources) {
        var descriptions = new ArrayList<String>();
        descriptions.add(targetDescription);
        for (var source : sources) {
            descriptions.add(source.description());
        }
        return List.copyOf(descriptions);
    }

    private static String mergeDescriptions(List<String> descriptions) {
        var values = new LinkedHashSet<String>();
        for (var description : descriptions) {
            if (description == null) {
                continue;
            }
            var normalized = description.strip();
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return String.join("\n\n", values);
    }

    private static List<String> mergedAliases(
        GraphStore.EntityRecord target,
        List<GraphStore.EntityRecord> sources
    ) {
        var aliases = new ArrayList<String>();
        aliases.addAll(target.aliases());
        for (var source : sources) {
            aliases.addAll(source.aliases());
            aliases.add(source.name());
        }
        return normalizeAliasesForName(target.name(), aliases);
    }

    private static List<String> mergeSourceChunkIds(
        GraphStore.EntityRecord target,
        List<GraphStore.EntityRecord> sources
    ) {
        var sourceChunkIds = new LinkedHashSet<String>();
        sourceChunkIds.addAll(target.sourceChunkIds());
        for (var source : sources) {
            sourceChunkIds.addAll(source.sourceChunkIds());
        }
        return List.copyOf(sourceChunkIds);
    }

    private static List<GraphStore.EntityRecord> mergeEntities(
        List<GraphStore.EntityRecord> entities,
        String targetEntityId,
        LinkedHashSet<String> sourceIds,
        GraphStore.EntityRecord mergedTarget
    ) {
        var values = new ArrayList<GraphStore.EntityRecord>(entities.size() - sourceIds.size());
        for (var entity : entities) {
            if (sourceIds.contains(entity.id())) {
                continue;
            }
            if (entity.id().equals(targetEntityId)) {
                values.add(mergedTarget);
            } else {
                values.add(entity);
            }
        }
        return List.copyOf(values);
    }

    private static List<GraphStore.RelationRecord> mergeRelations(
        List<GraphStore.RelationRecord> relations,
        LinkedHashSet<String> sourceIds,
        String targetEntityId
    ) {
        var merged = new LinkedHashMap<String, GraphStore.RelationRecord>();
        for (var relation : relations) {
            var sourceEntityId = sourceIds.contains(relation.sourceEntityId()) ? targetEntityId : relation.sourceEntityId();
            var targetRelationEntityId = sourceIds.contains(relation.targetEntityId()) ? targetEntityId : relation.targetEntityId();
            if (sourceEntityId.equals(targetRelationEntityId)) {
                continue;
            }
            var rewritten = new GraphStore.RelationRecord(
                relationId(sourceEntityId, relation.type(), targetRelationEntityId),
                sourceEntityId,
                targetRelationEntityId,
                relation.type(),
                relation.description(),
                relation.weight(),
                relation.sourceChunkIds()
            );
            merged.merge(rewritten.id(), rewritten, GraphManagementPipeline::mergeRelationRecord);
        }
        return List.copyOf(merged.values());
    }

    private static GraphStore.RelationRecord mergeRelationRecord(
        GraphStore.RelationRecord current,
        GraphStore.RelationRecord incoming
    ) {
        return new GraphStore.RelationRecord(
            current.id(),
            current.sourceEntityId(),
            current.targetEntityId(),
            current.type(),
            mergeDescriptions(List.of(current.description(), incoming.description())),
            Math.max(current.weight(), incoming.weight()),
            mergeSourceChunkIds(current.sourceChunkIds(), incoming.sourceChunkIds())
        );
    }

    private static List<String> mergeSourceChunkIds(List<String> current, List<String> incoming) {
        var values = new LinkedHashSet<String>();
        values.addAll(current);
        values.addAll(incoming);
        return List.copyOf(values);
    }

    private static String entityId(String entityName) {
        return "entity:" + normalizeKey(entityName);
    }

    private static String relationId(String sourceEntityId, String relationType, String targetEntityId) {
        return "relation:" + sourceEntityId + "|" + normalizeKey(relationType) + "|" + targetEntityId;
    }

    private static String normalizeKey(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptionalKey(String value) {
        Objects.requireNonNull(value, "value");
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            return null;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static Entity toEntity(GraphStore.EntityRecord entityRecord) {
        return new Entity(
            entityRecord.id(),
            entityRecord.name(),
            entityRecord.type(),
            entityRecord.description(),
            entityRecord.aliases(),
            entityRecord.sourceChunkIds()
        );
    }

    private static Relation toRelation(GraphStore.RelationRecord relationRecord) {
        return new Relation(
            relationRecord.id(),
            relationRecord.sourceEntityId(),
            relationRecord.targetEntityId(),
            relationRecord.type(),
            relationRecord.description(),
            relationRecord.weight(),
            relationRecord.sourceChunkIds()
        );
    }

    private static GraphEntity toGraphEntity(GraphStore.EntityRecord entityRecord) {
        return new GraphEntity(
            entityRecord.id(),
            entityRecord.name(),
            entityRecord.type(),
            entityRecord.description(),
            entityRecord.aliases(),
            entityRecord.sourceChunkIds()
        );
    }

    private static GraphRelation toGraphRelation(GraphStore.RelationRecord relationRecord) {
        return new GraphRelation(
            relationRecord.id(),
            relationRecord.sourceEntityId(),
            relationRecord.targetEntityId(),
            relationRecord.type(),
            relationRecord.description(),
            relationRecord.weight(),
            relationRecord.sourceChunkIds()
        );
    }

    private static List<GraphStore.EntityRecord> replaceEntity(
        List<GraphStore.EntityRecord> entities,
        String currentEntityId,
        GraphStore.EntityRecord updatedEntity
    ) {
        var values = new ArrayList<GraphStore.EntityRecord>(entities.size());
        for (var entity : entities) {
            if (!entity.id().equals(currentEntityId)) {
                values.add(entity);
            }
        }
        values.add(updatedEntity);
        return List.copyOf(values);
    }

    private static List<GraphStore.RelationRecord> replaceRelation(
        List<GraphStore.RelationRecord> relations,
        String currentRelationId,
        GraphStore.RelationRecord updatedRelation
    ) {
        var values = new ArrayList<GraphStore.RelationRecord>(relations.size());
        for (var relation : relations) {
            if (!relation.id().equals(currentRelationId)) {
                values.add(relation);
            }
        }
        values.add(updatedRelation);
        return List.copyOf(values);
    }

    private static List<GraphStore.RelationRecord> migrateRelations(
        List<GraphStore.RelationRecord> relations,
        String currentEntityId,
        String updatedEntityId
    ) {
        return relations.stream()
            .map(relation -> {
                if (!relation.sourceEntityId().equals(currentEntityId) && !relation.targetEntityId().equals(currentEntityId)) {
                    return relation;
                }
                var sourceEntityId = relation.sourceEntityId().equals(currentEntityId) ? updatedEntityId : relation.sourceEntityId();
                var targetEntityId = relation.targetEntityId().equals(currentEntityId) ? updatedEntityId : relation.targetEntityId();
                return new GraphStore.RelationRecord(
                    relationId(sourceEntityId, relation.type(), targetEntityId),
                    sourceEntityId,
                    targetEntityId,
                    relation.type(),
                    relation.description(),
                    relation.weight(),
                    relation.sourceChunkIds()
                );
            })
            .toList();
    }

    private static List<VectorStore.VectorRecord> replaceNamespaceVectors(
        List<VectorStore.VectorRecord> existingVectors,
        List<String> previousIds,
        List<VectorStore.VectorRecord> replacementVectors
    ) {
        var removedIds = new LinkedHashSet<>(previousIds);
        var values = new ArrayList<VectorStore.VectorRecord>(existingVectors.size() + replacementVectors.size());
        for (var vector : existingVectors) {
            if (!removedIds.contains(vector.id())) {
                values.add(vector);
            }
        }
        values.addAll(replacementVectors);
        return List.copyOf(values);
    }

    private void saveEntityVectors(VectorStore vectorStore, List<Entity> entities) {
        var vectors = indexingPipeline.entityVectors(entities);
        if (vectors.isEmpty()) {
            return;
        }
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(
                StorageSnapshots.ENTITY_NAMESPACE,
                HybridVectorPayloads.entityPayloads(entities, vectors)
            );
            return;
        }
        vectorStore.saveAll(StorageSnapshots.ENTITY_NAMESPACE, vectors);
    }

    private void saveRelationVectors(VectorStore vectorStore, List<Relation> relations) {
        var vectors = indexingPipeline.relationVectors(relations);
        if (vectors.isEmpty()) {
            return;
        }
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(
                StorageSnapshots.RELATION_NAMESPACE,
                HybridVectorPayloads.relationPayloads(relations, vectors)
            );
            return;
        }
        vectorStore.saveAll(StorageSnapshots.RELATION_NAMESPACE, vectors);
    }
}
