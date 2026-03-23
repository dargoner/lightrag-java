package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record ParentChildChunkSet(
    List<Chunk> parentChunks,
    List<Chunk> childChunks
) {
    public ParentChildChunkSet {
        parentChunks = List.copyOf(Objects.requireNonNull(parentChunks, "parentChunks"));
        childChunks = List.copyOf(Objects.requireNonNull(childChunks, "childChunks"));
    }

    public List<Chunk> searchableChunks() {
        var allChunks = allChunks();
        boolean hasExplicitSearchableFlag = allChunks.stream()
            .anyMatch(chunk -> chunk.metadata().containsKey(ParentChildChunkBuilder.METADATA_SEARCHABLE));
        var searchable = hasExplicitSearchableFlag
            ? allChunks.stream()
                .filter(chunk -> Boolean.parseBoolean(
                    chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_SEARCHABLE, Boolean.FALSE.toString())
                ))
                .toList()
            : (childChunks.isEmpty() ? parentChunks : childChunks);
        return normalizeOrders(searchable);
    }

    private static List<Chunk> normalizeOrders(List<Chunk> chunks) {
        var ordered = chunks.stream()
            .sorted(Comparator.comparingInt(Chunk::order).thenComparing(Chunk::id))
            .toList();
        return java.util.stream.IntStream.range(0, ordered.size())
            .mapToObj(index -> {
                var chunk = ordered.get(index);
                return new Chunk(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.text(),
                    chunk.tokenCount(),
                    index,
                    chunk.metadata()
                );
            })
            .toList();
    }

    public List<Chunk> allChunks() {
        return Stream.concat(parentChunks.stream(), childChunks.stream()).toList();
    }
}
