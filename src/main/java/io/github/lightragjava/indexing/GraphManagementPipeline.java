package io.github.lightragjava.indexing;

import io.github.lightragjava.api.CreateEntityRequest;
import io.github.lightragjava.api.CreateRelationRequest;
import io.github.lightragjava.api.GraphEntity;
import io.github.lightragjava.api.GraphRelation;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.Relation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        var snapshot = StorageSnapshots.capture(storageProvider);
        validateCreateEntity(snapshot.entities(), createRequest);

        var entityRecord = new GraphStore.EntityRecord(
            entityId(createRequest.name()),
            createRequest.name(),
            createRequest.type(),
            createRequest.description(),
            createRequest.aliases(),
            List.of()
        );
        var entityVectors = appendAll(
            snapshot.vectors().getOrDefault(StorageSnapshots.ENTITY_NAMESPACE, List.of()),
            indexingPipeline.entityVectors(List.of(toEntity(entityRecord)))
        );

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            append(snapshot.entities(), entityRecord),
            snapshot.relations(),
            java.util.Map.of(
                StorageSnapshots.CHUNK_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.CHUNK_NAMESPACE, List.of()),
                StorageSnapshots.ENTITY_NAMESPACE, entityVectors,
                StorageSnapshots.RELATION_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.RELATION_NAMESPACE, List.of())
            )
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphEntity(entityRecord);
    }

    public GraphRelation createRelation(CreateRelationRequest request) {
        var createRequest = Objects.requireNonNull(request, "request");
        var snapshot = StorageSnapshots.capture(storageProvider);
        var sourceEntity = resolveEntity(snapshot.entities(), createRequest.sourceEntityName(), "sourceEntityName");
        var targetEntity = resolveEntity(snapshot.entities(), createRequest.targetEntityName(), "targetEntityName");
        var relationRecord = new GraphStore.RelationRecord(
            relationId(sourceEntity.id(), createRequest.relationType(), targetEntity.id()),
            sourceEntity.id(),
            targetEntity.id(),
            createRequest.relationType(),
            createRequest.description(),
            createRequest.weight(),
            List.of()
        );
        validateCreateRelation(snapshot.relations(), relationRecord);

        var relationVectors = appendAll(
            snapshot.vectors().getOrDefault(StorageSnapshots.RELATION_NAMESPACE, List.of()),
            indexingPipeline.relationVectors(List.of(toRelation(relationRecord)))
        );

        storageProvider.restore(new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            snapshot.entities(),
            append(snapshot.relations(), relationRecord),
            java.util.Map.of(
                StorageSnapshots.CHUNK_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.CHUNK_NAMESPACE, List.of()),
                StorageSnapshots.ENTITY_NAMESPACE, snapshot.vectors().getOrDefault(StorageSnapshots.ENTITY_NAMESPACE, List.of()),
                StorageSnapshots.RELATION_NAMESPACE, relationVectors
            )
        ));
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        return toGraphRelation(relationRecord);
    }

    private static void validateCreateEntity(List<GraphStore.EntityRecord> entities, CreateEntityRequest request) {
        var requestedNameKey = normalizeKey(request.name());
        var requestedAliasKeys = aliasKeys(request.aliases());
        for (var entity : entities) {
            var existingNameKey = normalizeKey(entity.name());
            if (requestedNameKey.equals(existingNameKey) || requestedAliasKeys.contains(existingNameKey)) {
                throw new IllegalArgumentException("entity name or alias already exists: " + entity.name());
            }
            for (var alias : entity.aliases()) {
                var aliasKey = normalizeOptionalKey(alias);
                if (aliasKey != null && requestedAliasKeys.contains(aliasKey)) {
                    throw new IllegalArgumentException("entity name or alias already exists: " + alias.strip());
                }
            }
        }
    }

    private static void validateCreateRelation(
        List<GraphStore.RelationRecord> relations,
        GraphStore.RelationRecord relationRecord
    ) {
        if (relations.stream().anyMatch(existing -> existing.id().equals(relationRecord.id()))) {
            throw new IllegalArgumentException("relation already exists: " + relationRecord.id());
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

    private static <T> List<T> append(List<T> existing, T item) {
        var values = new ArrayList<T>(existing.size() + 1);
        values.addAll(existing);
        values.add(item);
        return List.copyOf(values);
    }

    private static List<VectorStore.VectorRecord> appendAll(
        List<VectorStore.VectorRecord> existing,
        List<VectorStore.VectorRecord> additions
    ) {
        if (additions.isEmpty()) {
            return List.copyOf(existing);
        }
        var values = new ArrayList<VectorStore.VectorRecord>(existing.size() + additions.size());
        values.addAll(existing);
        values.addAll(additions);
        return List.copyOf(values);
    }
}
