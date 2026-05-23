package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractionResult;

import java.util.List;
import java.util.Objects;

public record PrimaryChunkExtraction(
    Chunk chunk,
    ExtractionResult extraction,
    List<String> llmCacheIds
) {
    public PrimaryChunkExtraction(Chunk chunk, ExtractionResult extraction) {
        this(chunk, extraction, List.of());
    }

    public PrimaryChunkExtraction {
        chunk = Objects.requireNonNull(chunk, "chunk");
        extraction = Objects.requireNonNull(extraction, "extraction");
        llmCacheIds = List.copyOf(Objects.requireNonNull(llmCacheIds, "llmCacheIds"));
    }
}
