package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.List;

interface DocumentChunkPreparationStrategy {
    List<Chunk> prepare(Document document, Chunker chunker);
}
