package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.List;
import java.util.Objects;

final class DefaultChunkPreparationStrategy implements DocumentChunkPreparationStrategy {
    @Override
    public List<Chunk> prepare(Document document, Chunker chunker) {
        return Objects.requireNonNull(chunker, "chunker").chunk(Objects.requireNonNull(document, "document"));
    }
}
