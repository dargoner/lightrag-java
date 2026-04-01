package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;

import java.util.List;
import java.util.Objects;

public record ChunkingResult(
    List<Chunk> chunks,
    ChunkingMode effectiveMode,
    boolean downgradedToFixed,
    String fallbackReason
) {
    public ChunkingResult {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        effectiveMode = Objects.requireNonNull(effectiveMode, "effectiveMode");
    }
}
