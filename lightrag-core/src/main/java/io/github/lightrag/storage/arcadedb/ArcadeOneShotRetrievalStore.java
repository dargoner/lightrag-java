package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.OneShotRetrievalStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.indexing.RelationCanonicalizer;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ArcadeOneShotRetrievalStore extends ArcadeStoreSupport implements OneShotRetrievalStore {
    ArcadeOneShotRetrievalStore(ArcadeDbClient client, String workspaceId) {
        super(client, workspaceId);
    }

    @Override
    public boolean supportsMixRetrieval() {
        return true;
    }

    @Override
    public LocalRetrievalResult retrieveLocal(List<VectorStore.VectorMatch> entityMatches) {
        var entityScores = scores(entityMatches);
        if (entityScores.isEmpty()) {
            return new LocalRetrievalResult(List.of(), List.of(), List.of());
        }

        var relationScores = new LinkedHashMap<String, Double>();
        var relationsById = new LinkedHashMap<String, Relation>();
        for (var relation : loadRelationsForEntities(entityScores.keySet())) {
            var relationScore = Math.max(
                entityScores.getOrDefault(relation.srcId(), 0.0d),
                entityScores.getOrDefault(relation.tgtId(), 0.0d)
            );
            relationScores.merge(relation.id(), relationScore, Math::max);
            relationsById.put(relation.id(), relation);
            entityScores.merge(relation.srcId(), relationScore, Math::max);
            entityScores.merge(relation.tgtId(), relationScore, Math::max);
        }

        var entities = loadEntities(entityScores.keySet()).stream()
            .map(entity -> new ScoredEntity(entity.id(), entity, entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        var relations = relationsById.values().stream()
            .map(relation -> new ScoredRelation(relation.id(), relation, relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();
        return new LocalRetrievalResult(entities, relations, loadChunks(chunkScores(entities, relations)));
    }

    @Override
    public GlobalRetrievalResult retrieveGlobal(List<VectorStore.VectorMatch> relationMatches) {
        var relationScores = scores(relationMatches);
        if (relationScores.isEmpty()) {
            return new GlobalRetrievalResult(List.of(), List.of(), List.of());
        }

        var relations = loadRelations(relationScores.keySet()).stream()
            .map(relation -> new ScoredRelation(relation.id(), relation, relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();
        var entityScores = new LinkedHashMap<String, Double>();
        var chunkScores = new LinkedHashMap<String, Double>();
        for (var relation : relations) {
            entityScores.merge(relation.relation().srcId(), relation.score(), Math::max);
            entityScores.merge(relation.relation().tgtId(), relation.score(), Math::max);
            for (var chunkId : relation.relation().sourceChunkIds()) {
                chunkScores.merge(chunkId, relation.score(), Math::max);
            }
        }
        var entities = loadEntities(entityScores.keySet()).stream()
            .map(entity -> new ScoredEntity(entity.id(), entity, entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        return new GlobalRetrievalResult(entities, relations, loadChunks(chunkScores));
    }

    @Override
    public DirectChunkRetrievalResult retrieveChunks(List<VectorStore.VectorMatch> chunkMatches) {
        var chunkScores = scores(chunkMatches);
        if (chunkScores.isEmpty()) {
            return new DirectChunkRetrievalResult(List.of());
        }
        return new DirectChunkRetrievalResult(loadChunks(chunkScores));
    }

    @Override
    public MixRetrievalResult retrieveMix(
        List<VectorStore.VectorMatch> entityMatches,
        List<VectorStore.VectorMatch> relationMatches,
        List<VectorStore.VectorMatch> chunkMatches
    ) {
        var entityScores = scores(entityMatches);
        var relationScores = scores(relationMatches);
        var directChunkScores = scores(chunkMatches);
        if (entityScores.isEmpty() && relationScores.isEmpty() && directChunkScores.isEmpty()) {
            return new MixRetrievalResult(List.of(), List.of(), List.of(), List.of());
        }

        var relationsById = new LinkedHashMap<String, Relation>();
        if (!entityScores.isEmpty()) {
            for (var relation : loadRelationsForEntities(entityScores.keySet())) {
                var relationScore = Math.max(
                    entityScores.getOrDefault(relation.srcId(), 0.0d),
                    entityScores.getOrDefault(relation.tgtId(), 0.0d)
                );
                relationScores.merge(relation.id(), relationScore, Math::max);
                relationsById.put(relation.id(), relation);
                entityScores.merge(relation.srcId(), relationScore, Math::max);
                entityScores.merge(relation.tgtId(), relationScore, Math::max);
            }
        }
        if (!relationScores.isEmpty()) {
            for (var relation : loadRelations(relationScores.keySet())) {
                relationsById.put(relation.id(), relation);
                var relationScore = relationScores.getOrDefault(relation.id(), 0.0d);
                entityScores.merge(relation.srcId(), relationScore, Math::max);
                entityScores.merge(relation.tgtId(), relationScore, Math::max);
            }
        }

        var entities = loadEntities(entityScores.keySet()).stream()
            .map(entity -> new ScoredEntity(entity.id(), entity, entityScores.getOrDefault(entity.id(), 0.0d)))
            .sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId))
            .toList();
        var relations = relationsById.values().stream()
            .map(relation -> new ScoredRelation(relation.id(), relation, relationScores.getOrDefault(relation.id(), 0.0d)))
            .sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId))
            .toList();
        return new MixRetrievalResult(
            entities,
            relations,
            loadChunks(chunkScores(entities, relations)),
            loadChunks(directChunkScores)
        );
    }

    private Map<String, Double> scores(List<VectorStore.VectorMatch> matches) {
        var scores = new LinkedHashMap<String, Double>();
        for (var match : Objects.requireNonNull(matches, "matches")) {
            scores.merge(match.id(), match.score(), Math::max);
        }
        return scores;
    }

    private List<Entity> loadEntities(java.util.Collection<String> entityIds) {
        var ids = List.copyOf(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return query(
            """
            SELECT id, name, type, description, aliases, sourceChunkIds
            FROM Entity
            WHERE workspaceId = :workspaceId
              AND id IN :ids
            """,
            Map.of("workspaceId", workspaceId, "ids", ids)
        ).stream()
            .map(this::readEntity)
            .toList();
    }

    private List<Relation> loadRelations(java.util.Collection<String> relationIds) {
        var ids = List.copyOf(relationIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return query(
            """
            SELECT id, srcId, tgtId, keywords, description, weight, sourceId, filePath
            FROM Relation
            WHERE workspaceId = :workspaceId
              AND id IN :ids
            """,
            Map.of("workspaceId", workspaceId, "ids", ids)
        ).stream()
            .map(this::readRelation)
            .toList();
    }

    private List<Relation> loadRelationsForEntities(java.util.Collection<String> entityIds) {
        var ids = List.copyOf(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return query(
            """
            SELECT id, srcId, tgtId, keywords, description, weight, sourceId, filePath
            FROM Relation
            WHERE workspaceId = :workspaceId
              AND (srcId IN :ids OR tgtId IN :ids)
            """,
            Map.of("workspaceId", workspaceId, "ids", ids)
        ).stream()
            .map(this::readRelation)
            .toList();
    }

    private List<ScoredChunk> loadChunks(Map<String, Double> chunkScores) {
        if (chunkScores.isEmpty()) {
            return List.of();
        }
        return query(
            """
            SELECT id, documentId, text, tokenCount, chunkOrder, metadata
            FROM Chunk
            WHERE workspaceId = :workspaceId
              AND id IN :ids
            """,
            Map.of("workspaceId", workspaceId, "ids", List.copyOf(chunkScores.keySet()))
        ).stream()
            .map(row -> readChunk(row, chunkScores))
            .sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId))
            .toList();
    }

    private Map<String, Double> chunkScores(List<ScoredEntity> entities, List<ScoredRelation> relations) {
        var chunkScores = new LinkedHashMap<String, Double>();
        for (var entity : entities) {
            for (var chunkId : entity.entity().sourceChunkIds()) {
                chunkScores.merge(chunkId, entity.score(), Math::max);
            }
        }
        for (var relation : relations) {
            for (var chunkId : relation.relation().sourceChunkIds()) {
                chunkScores.merge(chunkId, relation.score(), Math::max);
            }
        }
        return chunkScores;
    }

    private Entity readEntity(Map<String, Object> row) {
        return new Entity(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "name"),
            ArcadeRecordMapper.string(row, "type"),
            ArcadeRecordMapper.string(row, "description"),
            ArcadeRecordMapper.stringList(row, "aliases"),
            ArcadeRecordMapper.stringList(row, "sourceChunkIds")
        );
    }

    private Relation readRelation(Map<String, Object> row) {
        return new Relation(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "srcId"),
            ArcadeRecordMapper.string(row, "tgtId"),
            ArcadeRecordMapper.string(row, "keywords"),
            ArcadeRecordMapper.string(row, "description"),
            ArcadeRecordMapper.decimal(row, "weight"),
            RelationCanonicalizer.splitValues(ArcadeRecordMapper.string(row, "sourceId"))
        );
    }

    private ScoredChunk readChunk(Map<String, Object> row, Map<String, Double> chunkScores) {
        var chunk = new Chunk(
            ArcadeRecordMapper.string(row, "id"),
            ArcadeRecordMapper.string(row, "documentId"),
            ArcadeRecordMapper.string(row, "text"),
            ArcadeRecordMapper.integer(row, "tokenCount"),
            ArcadeRecordMapper.integer(row, "chunkOrder"),
            ArcadeRecordMapper.stringMap(row, "metadata")
        );
        return new ScoredChunk(chunk.id(), chunk, chunkScores.getOrDefault(chunk.id(), 0.0d));
    }

    private static <T> Comparator<T> scoreOrder(
        java.util.function.ToDoubleFunction<T> scoreExtractor,
        java.util.function.Function<T, String> idExtractor
    ) {
        return Comparator.comparingDouble(scoreExtractor).reversed().thenComparing(idExtractor);
    }
}
