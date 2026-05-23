package io.github.lightrag.indexing;

import java.util.List;

public interface ChunkTextTokenizer {
    List<String> encode(String text);

    String decode(List<String> tokens);

    int count(String text);
}
