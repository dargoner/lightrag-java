package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RecursiveCharacterChunker {
    public static final List<String> DEFAULT_SEPARATORS = List.of(
        "\n\n",
        "\n",
        "\u3002",
        "\uff01",
        "\uff1f",
        "\uff1b",
        "\uff0c",
        " ",
        ""
    );

    private final int chunkSize;
    private final int overlap;
    private final ChunkTextTokenizer tokenizer;
    private final List<String> separators;

    public RecursiveCharacterChunker(int chunkSize, int overlap) {
        this(chunkSize, overlap, UnicodeCodePointChunkTextTokenizer.INSTANCE, DEFAULT_SEPARATORS);
    }

    public RecursiveCharacterChunker(
        int chunkSize,
        int overlap,
        ChunkTextTokenizer tokenizer,
        List<String> separators
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap must be non-negative");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be smaller than chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.separators = List.copyOf(Objects.requireNonNull(separators, "separators"));
    }

    public ChunkingResult chunk(ParsedDocument document) {
        var parsed = Objects.requireNonNull(document, "document");
        var source = new Document(parsed.documentId(), parsed.title(), parsed.plainText(), parsed.metadata());
        return new ChunkingResult(chunk(source), ChunkingMode.RECURSIVE, false, null);
    }

    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isBlank()) {
            return List.of();
        }
        var pieces = splitRecursive(source.content(), 0).stream()
            .map(String::strip)
            .filter(piece -> !piece.isBlank())
            .toList();
        var chunkTexts = mergePieces(pieces);
        var chunks = new ArrayList<Chunk>(chunkTexts.size());
        for (int order = 0; order < chunkTexts.size(); order++) {
            var text = chunkTexts.get(order);
            chunks.add(new Chunk(
                source.id() + ":" + order,
                source.id(),
                text,
                tokenizer.count(text),
                order,
                source.metadata()
            ));
        }
        return List.copyOf(chunks);
    }

    private List<String> splitRecursive(String text, int separatorIndex) {
        if (tokenizer.count(text) <= chunkSize) {
            return List.of(text);
        }
        if (separatorIndex >= separators.size()) {
            return tokenAtoms(text);
        }
        var separator = separators.get(separatorIndex);
        if (separator.isEmpty()) {
            return tokenAtoms(text);
        }
        if (!text.contains(separator)) {
            return splitRecursive(text, separatorIndex + 1);
        }
        var split = text.split(Pattern.quote(separator), -1);
        var pieces = new ArrayList<String>();
        for (int index = 0; index < split.length; index++) {
            var segment = split[index];
            if (segment.isEmpty()) {
                continue;
            }
            var restored = index + 1 < split.length ? segment + separator : segment;
            if (tokenizer.count(restored) > chunkSize) {
                pieces.addAll(splitRecursive(restored, separatorIndex + 1));
            } else {
                pieces.add(restored);
            }
        }
        return List.copyOf(pieces);
    }

    private List<String> mergePieces(List<String> pieces) {
        if (pieces.isEmpty()) {
            return List.of();
        }
        var chunks = new ArrayList<String>();
        var current = "";
        for (var piece : pieces) {
            if (current.isBlank()) {
                current = piece;
                continue;
            }
            var candidate = current + piece;
            if (tokenizer.count(candidate) <= chunkSize) {
                current = candidate;
                continue;
            }
            chunks.add(current.strip());
            current = overlapPrefix(current) + piece;
            if (tokenizer.count(current) > chunkSize) {
                chunks.addAll(mergePieces(tokenAtoms(current)));
                current = "";
            }
        }
        if (!current.isBlank()) {
            chunks.add(current.strip());
        }
        return List.copyOf(chunks);
    }

    private String overlapPrefix(String current) {
        if (overlap <= 0 || current.isBlank()) {
            return "";
        }
        var tokens = tokenizer.encode(current);
        var start = Math.max(0, tokens.size() - overlap);
        return tokenizer.decode(tokens.subList(start, tokens.size()));
    }

    private List<String> tokenWindowSplit(String text) {
        return new FixedWindowChunker(chunkSize, overlap, tokenizer)
            .chunk(new Document("recursive-fallback", "", text, Map.of()))
            .stream()
            .map(Chunk::text)
            .toList();
    }

    private List<String> tokenAtoms(String text) {
        return tokenizer.encode(text).stream()
            .filter(token -> !token.isBlank())
            .toList();
    }
}
