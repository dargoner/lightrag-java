package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.List;

interface DocumentChunkPreparationStrategy {
    List<Chunk> prepare(Document document, Chunker chunker);
}
