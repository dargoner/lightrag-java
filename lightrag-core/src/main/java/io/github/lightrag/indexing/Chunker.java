package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.List;

public interface Chunker {
    List<Chunk> chunk(Document document);
}
