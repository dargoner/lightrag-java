package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractionResult;

import java.util.Objects;

public record PrimaryChunkExtraction(
    Chunk chunk,
    ExtractionResult extraction
) {
    public PrimaryChunkExtraction {
        chunk = Objects.requireNonNull(chunk, "chunk");
        extraction = Objects.requireNonNull(extraction, "extraction");
    }
}
