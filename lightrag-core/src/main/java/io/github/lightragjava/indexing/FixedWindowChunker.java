package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;
import io.github.lightragjava.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class FixedWindowChunker implements Chunker {
    public static final int DEFAULT_WINDOW_SIZE = 1_000;
    public static final int DEFAULT_OVERLAP = 100;

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

        var codePointBoundaries = codePointBoundaries(source.content());
        var codePointCount = codePointBoundaries.length - 1;
        var chunks = new ArrayList<Chunk>();
        var start = 0;
        var order = 0;

        while (start < codePointCount) {
            var end = Math.min(codePointCount, start + windowSize);
            var text = sliceByCodePoint(source.content(), codePointBoundaries, start, end);
            chunks.add(new Chunk(
                composeChunkId(source.id(), order),
                source.id(),
                text,
                text.codePointCount(0, text.length()),
                order,
                source.metadata()
            ));
            if (end == codePointCount) {
                break;
            }
            start = end - overlap;
            order++;
        }

        return List.copyOf(chunks);
    }

    private static String composeChunkId(String documentId, int order) {
        return documentId + ":" + order;
    }

    private static String sliceByCodePoint(String content, int[] boundaries, int start, int end) {
        return content.substring(boundaries[start], boundaries[end]);
    }

    private static int[] codePointBoundaries(String content) {
        var codePointCount = content.codePointCount(0, content.length());
        var boundaries = new int[codePointCount + 1];
        var charIndex = 0;
        var codePointIndex = 0;

        while (charIndex < content.length()) {
            boundaries[codePointIndex] = charIndex;
            charIndex += Character.charCount(content.codePointAt(charIndex));
            codePointIndex++;
        }
        boundaries[codePointIndex] = content.length();
        return boundaries;
    }
}
