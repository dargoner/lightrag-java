package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class FixedWindowChunker implements Chunker {
    public static final int DEFAULT_WINDOW_SIZE = 1_000;
    public static final int DEFAULT_OVERLAP = 100;

    private final int windowSize;
    private final int overlap;
    private final ChunkTextTokenizer tokenizer;
    private final String splitByCharacter;
    private final boolean splitByCharacterOnly;

    public FixedWindowChunker(int windowSize, int overlap) {
        this(windowSize, overlap, UnicodeCodePointChunkTextTokenizer.INSTANCE);
    }

    public FixedWindowChunker(int windowSize, int overlap, ChunkTextTokenizer tokenizer) {
        this(windowSize, overlap, tokenizer, null, false);
    }

    public FixedWindowChunker(
        int windowSize,
        int overlap,
        ChunkTextTokenizer tokenizer,
        String splitByCharacter,
        boolean splitByCharacterOnly
    ) {
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
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.splitByCharacter = splitByCharacter == null || splitByCharacter.isEmpty() ? null : splitByCharacter;
        this.splitByCharacterOnly = splitByCharacterOnly;
    }

    @Override
    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isEmpty()) {
            return List.of();
        }

        var chunkTexts = splitByCharacter == null
            ? splitTokenWindow(source.content())
            : splitByConfiguredCharacter(source.content());
        var chunks = new ArrayList<Chunk>();
        for (int order = 0; order < chunkTexts.size(); order++) {
            var text = chunkTexts.get(order);
            chunks.add(new Chunk(
                composeChunkId(source.id(), order),
                source.id(),
                text,
                tokenizer.count(text),
                order,
                source.metadata()
            ));
        }

        return List.copyOf(chunks);
    }

    private List<String> splitByConfiguredCharacter(String content) {
        var chunks = new ArrayList<String>();
        for (var rawChunk : content.split(Pattern.quote(splitByCharacter), -1)) {
            var tokens = tokenizer.encode(rawChunk);
            if (splitByCharacterOnly) {
                if (tokens.size() > windowSize) {
                    throw new IllegalArgumentException(
                        "Chunk split_by_character exceeds token limit: len="
                            + tokens.size()
                            + " limit="
                            + windowSize
                    );
                }
                chunks.add(rawChunk.strip());
                continue;
            }
            if (tokens.size() > windowSize) {
                chunks.addAll(splitTokenWindow(tokens));
            } else {
                chunks.add(rawChunk.strip());
            }
        }
        return List.copyOf(chunks);
    }

    private List<String> splitTokenWindow(String content) {
        return splitTokenWindow(tokenizer.encode(content));
    }

    private List<String> splitTokenWindow(List<String> tokens) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        var chunks = new ArrayList<String>();
        var step = windowSize - overlap;
        for (int start = 0; start < tokens.size(); start += step) {
            var end = Math.min(tokens.size(), start + windowSize);
            chunks.add(tokenizer.decode(tokens.subList(start, end)).strip());
            if (end == tokens.size()) {
                break;
            }
        }
        return List.copyOf(chunks);
    }

    private static String composeChunkId(String documentId, int order) {
        return documentId + ":" + order;
    }

}
