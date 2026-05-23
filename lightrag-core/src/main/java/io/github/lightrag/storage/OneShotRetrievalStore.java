package io.github.lightrag.storage;

import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public interface OneShotRetrievalStore {
    default boolean supportsMixRetrieval() {
        return false;
    }

    LocalRetrievalResult retrieveLocal(List<VectorStore.VectorMatch> entityMatches);

    GlobalRetrievalResult retrieveGlobal(List<VectorStore.VectorMatch> relationMatches);

    DirectChunkRetrievalResult retrieveChunks(List<VectorStore.VectorMatch> chunkMatches);

    default MixRetrievalResult retrieveMix(
        List<VectorStore.VectorMatch> entityMatches,
        List<VectorStore.VectorMatch> relationMatches,
        List<VectorStore.VectorMatch> chunkMatches
    ) {
        var local = retrieveLocal(entityMatches);
        var global = retrieveGlobal(relationMatches);
        var direct = retrieveChunks(chunkMatches);
        var entities = new LinkedHashMap<String, ScoredEntity>();
        for (var entity : local.entities()) {
            entities.merge(entity.entityId(), entity, OneShotRetrievalStore::pickEntity);
        }
        for (var entity : global.entities()) {
            entities.merge(entity.entityId(), entity, OneShotRetrievalStore::pickEntity);
        }
        var relations = new LinkedHashMap<String, ScoredRelation>();
        for (var relation : local.relations()) {
            relations.merge(relation.relationId(), relation, OneShotRetrievalStore::pickRelation);
        }
        for (var relation : global.relations()) {
            relations.merge(relation.relationId(), relation, OneShotRetrievalStore::pickRelation);
        }
        var graphChunks = new LinkedHashMap<String, ScoredChunk>();
        for (var chunk : local.chunks()) {
            graphChunks.merge(chunk.chunkId(), chunk, OneShotRetrievalStore::pickChunk);
        }
        for (var chunk : global.chunks()) {
            graphChunks.merge(chunk.chunkId(), chunk, OneShotRetrievalStore::pickChunk);
        }
        return new MixRetrievalResult(
            entities.values().stream().sorted(scoreOrder(ScoredEntity::score, ScoredEntity::entityId)).toList(),
            relations.values().stream().sorted(scoreOrder(ScoredRelation::score, ScoredRelation::relationId)).toList(),
            graphChunks.values().stream().sorted(scoreOrder(ScoredChunk::score, ScoredChunk::chunkId)).toList(),
            direct.chunks()
        );
    }

    private static ScoredEntity pickEntity(ScoredEntity left, ScoredEntity right) {
        return left.score() >= right.score() ? left : right;
    }

    private static ScoredRelation pickRelation(ScoredRelation left, ScoredRelation right) {
        return left.score() >= right.score() ? left : right;
    }

    private static ScoredChunk pickChunk(ScoredChunk left, ScoredChunk right) {
        return left.score() >= right.score() ? left : right;
    }

    private static <T> Comparator<T> scoreOrder(
        java.util.function.ToDoubleFunction<T> scoreExtractor,
        java.util.function.Function<T, String> idExtractor
    ) {
        return Comparator.comparingDouble(scoreExtractor).reversed().thenComparing(idExtractor);
    }

    record LocalRetrievalResult(
        List<ScoredEntity> entities,
        List<ScoredRelation> relations,
        List<ScoredChunk> chunks
    ) {
        public LocalRetrievalResult {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }

    record GlobalRetrievalResult(
        List<ScoredEntity> entities,
        List<ScoredRelation> relations,
        List<ScoredChunk> chunks
    ) {
        public GlobalRetrievalResult {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }

    record DirectChunkRetrievalResult(List<ScoredChunk> chunks) {
        public DirectChunkRetrievalResult {
            chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        }
    }

    record MixRetrievalResult(
        List<ScoredEntity> entities,
        List<ScoredRelation> relations,
        List<ScoredChunk> graphChunks,
        List<ScoredChunk> directChunks
    ) {
        public MixRetrievalResult {
            entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
            relations = List.copyOf(Objects.requireNonNull(relations, "relations"));
            graphChunks = List.copyOf(Objects.requireNonNull(graphChunks, "graphChunks"));
            directChunks = List.copyOf(Objects.requireNonNull(directChunks, "directChunks"));
        }
    }
}
