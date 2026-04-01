package io.github.lightrag.indexing;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class RegexChunker {
    private final FixedWindowChunker fallbackChunker;

    public RegexChunker(FixedWindowChunker fallbackChunker) {
        this.fallbackChunker = Objects.requireNonNull(fallbackChunker, "fallbackChunker");
    }

    public ChunkingResult chunk(ParsedDocument document, RegexChunkerConfig config) {
        var parsed = Objects.requireNonNull(document, "document");
        var rules = Objects.requireNonNull(config, "config");
        if (parsed.plainText().isEmpty()) {
            return new ChunkingResult(List.of(), ChunkingMode.REGEX, false, null);
        }
        if (!rules.hasRules()) {
            return fallback(parsed, "regex rules are not configured");
        }

        var boundaries = new TreeSet<Integer>();
        boundaries.add(0);
        for (var rule : rules.rules()) {
            var matcher = rule.compile().matcher(parsed.plainText());
            while (matcher.find()) {
                if (matcher.start() > 0) {
                    boundaries.add(matcher.start());
                }
            }
        }
        if (boundaries.size() <= 1) {
            return fallback(parsed, "regex rules produced no valid boundaries");
        }

        var offsets = List.copyOf(boundaries);
        var chunks = new ArrayList<Chunk>();
        for (int index = 0; index < offsets.size(); index++) {
            int start = offsets.get(index);
            int end = index + 1 < offsets.size() ? offsets.get(index + 1) : parsed.plainText().length();
            var text = parsed.plainText().substring(start, end).strip();
            if (text.isEmpty()) {
                continue;
            }
            chunks.add(new Chunk(
                parsed.documentId() + ":" + chunks.size(),
                parsed.documentId(),
                text,
                text.codePointCount(0, text.length()),
                chunks.size(),
                parsed.metadata()
            ));
        }
        if (chunks.size() <= 1) {
            return fallback(parsed, "regex rules produced no valid boundaries");
        }
        return new ChunkingResult(List.copyOf(chunks), ChunkingMode.REGEX, false, null);
    }

    private ChunkingResult fallback(ParsedDocument document, String reason) {
        var fallbackDocument = new Document(
            document.documentId(),
            document.title(),
            document.plainText(),
            document.metadata()
        );
        return new ChunkingResult(
            fallbackChunker.chunk(fallbackDocument),
            ChunkingMode.FIXED,
            true,
            reason
        );
    }
}
