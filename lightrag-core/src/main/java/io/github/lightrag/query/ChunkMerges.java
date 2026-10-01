package io.github.lightrag.query;

import io.github.lightrag.types.ScoredChunk;
import io.github.lightrag.types.ScoredEntity;
import io.github.lightrag.types.ScoredRelation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

final class ChunkMerges {
    private ChunkMerges() {
    }

    static List<ScoredChunk> roundRobinChunks(List<List<ScoredChunk>> sources) {
        return roundRobin(sources, ScoredChunk::chunkId);
    }

    static List<ScoredEntity> roundRobinEntities(List<ScoredEntity> first, List<ScoredEntity> second) {
        return roundRobin(List.of(first, second), ScoredEntity::entityId);
    }

    static List<ScoredRelation> roundRobinRelations(List<ScoredRelation> first, List<ScoredRelation> second) {
        return roundRobin(List.of(first, second), ScoredRelation::relationId);
    }

    private static <T> List<T> roundRobin(List<List<T>> sources, Function<T, String> idExtractor) {
        var seen = new LinkedHashSet<String>();
        var merged = new ArrayList<T>();
        var maxLength = sources.stream().mapToInt(List::size).max().orElse(0);
        for (var index = 0; index < maxLength; index++) {
            for (var source : sources) {
                if (index < source.size()) {
                    var item = source.get(index);
                    if (seen.add(idExtractor.apply(item))) {
                        merged.add(item);
                    }
                }
            }
        }
        return List.copyOf(merged);
    }
}
