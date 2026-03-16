package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.List;

public interface Chunker {
    List<Chunk> chunk(Document document);
}
