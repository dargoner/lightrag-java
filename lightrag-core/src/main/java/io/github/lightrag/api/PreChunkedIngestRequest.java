package io.github.lightrag.api;

import io.github.lightrag.types.PreChunkedChunk;

import java.util.List;
import java.util.Objects;

public record PreChunkedIngestRequest(List<PreChunkedChunk> chunks) {
    public PreChunkedIngestRequest {
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }

    public static PreChunkedIngestRequest ofChunks(List<PreChunkedChunk> chunks) {
        return new PreChunkedIngestRequest(chunks);
    }
}
