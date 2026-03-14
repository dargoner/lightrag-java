package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FixedWindowChunker implements Chunker {
    private final int windowSize;
    private final int overlap;

    public FixedWindowChunker(int windowSize, int overlap) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap must be non-negative");
        }
        if (overlap >= windowSize) {
            throw new IllegalArgumentException("overlap must be smaller than windowSize");
        }
        this.windowSize = windowSize;
        this.overlap = overlap;
    }

    @Override
    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isEmpty()) {
            return List.of();
        }

        var step = windowSize - overlap;
        var chunks = new ArrayList<Chunk>();
        var content = source.content();
        var start = 0;
        var order = 0;

        while (start < content.length()) {
            var end = Math.min(content.length(), start + windowSize);
            var text = content.substring(start, end);
            chunks.add(new Chunk(
                composeChunkId(source.id(), order),
                source.id(),
                text,
                text.length(),
                order,
                source.metadata()
            ));
            if (end == content.length()) {
                break;
            }
            start += step;
            order++;
        }

        return List.copyOf(chunks);
    }

    private static String composeChunkId(String documentId, int order) {
        return documentId + ":" + order;
    }
}
