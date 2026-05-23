package io.github.lightrag.api;

import io.github.lightrag.indexing.ChunkingStrategyOverride;

import java.util.Objects;

public record ProcessOptions(
    String value,
    boolean imageAnalysis,
    boolean tableAnalysis,
    boolean equationAnalysis,
    boolean skipKnowledgeGraph,
    ChunkingStrategyOverride chunkingStrategy
) {
    public ProcessOptions {
        value = sanitize(value);
        var parsed = parse(value);
        imageAnalysis = parsed.imageAnalysis;
        tableAnalysis = parsed.tableAnalysis;
        equationAnalysis = parsed.equationAnalysis;
        skipKnowledgeGraph = parsed.skipKnowledgeGraph;
        chunkingStrategy = parsed.chunkingStrategy;
    }

    public static ProcessOptions defaults() {
        return new ProcessOptions("");
    }

    public ProcessOptions(String value) {
        this(value, false, false, false, false, ChunkingStrategyOverride.AUTO);
    }

    public ProcessOptions withChunkingStrategy(ChunkingStrategyOverride strategy) {
        var selector = selectorFor(Objects.requireNonNull(strategy, "strategy"));
        if (selector.isEmpty() || chunkingStrategy != ChunkingStrategyOverride.AUTO) {
            return this;
        }
        return new ProcessOptions(value + selector);
    }

    public static String selectorFor(ChunkingStrategyOverride strategy) {
        return switch (Objects.requireNonNull(strategy, "strategy")) {
            case FIXED -> "F";
            case RECURSIVE -> "R";
            case SEMANTIC_VECTOR -> "V";
            case PARAGRAPH -> "P";
            case AUTO, SMART, REGEX -> "";
        };
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        var raw = value.strip();
        var builder = new StringBuilder(raw.length());
        boolean image = false;
        boolean table = false;
        boolean equation = false;
        boolean skipKg = false;
        Character chunking = null;
        for (int i = 0; i < raw.length(); i++) {
            var ch = raw.charAt(i);
            switch (ch) {
                case 'i' -> {
                    if (!image) {
                        builder.append(ch);
                        image = true;
                    }
                }
                case 't' -> {
                    if (!table) {
                        builder.append(ch);
                        table = true;
                    }
                }
                case 'e' -> {
                    if (!equation) {
                        builder.append(ch);
                        equation = true;
                    }
                }
                case '!' -> {
                    if (!skipKg) {
                        builder.append(ch);
                        skipKg = true;
                    }
                }
                case 'F', 'R', 'V', 'P' -> {
                    if (chunking != null && chunking != ch) {
                        throw new IllegalArgumentException("process_options must contain at most one chunking selector");
                    }
                    if (chunking == null) {
                        builder.append(ch);
                        chunking = ch;
                    }
                }
                default -> throw new IllegalArgumentException("unsupported process_options flag: " + ch);
            }
        }
        return builder.toString();
    }

    private static Parsed parse(String value) {
        var parsed = new Parsed();
        for (int i = 0; i < value.length(); i++) {
            switch (value.charAt(i)) {
                case 'i' -> parsed.imageAnalysis = true;
                case 't' -> parsed.tableAnalysis = true;
                case 'e' -> parsed.equationAnalysis = true;
                case '!' -> parsed.skipKnowledgeGraph = true;
                case 'F' -> parsed.chunkingStrategy = ChunkingStrategyOverride.FIXED;
                case 'R' -> parsed.chunkingStrategy = ChunkingStrategyOverride.RECURSIVE;
                case 'V' -> parsed.chunkingStrategy = ChunkingStrategyOverride.SEMANTIC_VECTOR;
                case 'P' -> parsed.chunkingStrategy = ChunkingStrategyOverride.PARAGRAPH;
                default -> throw new IllegalArgumentException("unsupported process_options flag: " + value.charAt(i));
            }
        }
        return parsed;
    }

    private static final class Parsed {
        boolean imageAnalysis;
        boolean tableAnalysis;
        boolean equationAnalysis;
        boolean skipKnowledgeGraph;
        ChunkingStrategyOverride chunkingStrategy = ChunkingStrategyOverride.AUTO;
    }
}
