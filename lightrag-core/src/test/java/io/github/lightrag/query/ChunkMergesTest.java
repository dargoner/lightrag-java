package io.github.lightrag.query;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;
import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkMergesTest {
    @Test
    void interleavesSourcesAndKeepsTheFirstOccurrence() {
        var local = List.of(chunk("a", 0.9d), chunk("b", 0.8d));
        var global = List.of(chunk("b", 0.7d), chunk("c", 0.6d));
        assertThat(ChunkMerges.roundRobinChunks(List.of(local, global)))
            .extracting(ScoredChunk::chunkId)
            .containsExactly("a", "b", "c");
    }

    @Test
    void handlesUnevenSourceLengths() {
        var first = List.of(chunk("a", 1.0d));
        var second = List.of(chunk("b", 1.0d), chunk("c", 1.0d), chunk("d", 1.0d));
        assertThat(ChunkMerges.roundRobinChunks(List.of(first, second)))
            .extracting(ScoredChunk::chunkId)
            .containsExactly("a", "b", "c", "d");
    }

    @Test
    void interleavesEntitiesAndRelationsByTheirIdentity() {
        assertThat(ChunkMerges.roundRobinEntities(List.of(entity("e1"), entity("e2")), List.of(entity("e2"), entity("e3"))))
            .extracting(ScoredEntity::entityId)
            .containsExactly("e1", "e2", "e3");
        assertThat(ChunkMerges.roundRobinRelations(List.of(relation("r1")), List.of(relation("r2"))))
            .extracting(ScoredRelation::relationId)
            .containsExactly("r1", "r2");
    }

    private static ScoredChunk chunk(String chunkId, double score) {
        return new ScoredChunk(chunkId, new Chunk(chunkId, "doc-1", "text-" + chunkId, 2, 0, Map.of()), score);
    }

    private static ScoredEntity entity(String entityId) {
        return new ScoredEntity(entityId, new Entity(entityId, entityId, "person", "description", List.of(), List.of()), 1.0d);
    }

    private static ScoredRelation relation(String relationId) {
        return new ScoredRelation(
            relationId,
            new Relation(relationId, "src", "tgt", "related_to", "description", 1.0d, List.of()),
            1.0d
        );
    }
}
