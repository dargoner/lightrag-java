package io.github.lightrag.indexing;

import java.util.Locale;

public enum ChunkingStrategyOverride {
    AUTO,
    SMART,
    PARAGRAPH,
    RECURSIVE,
    SEMANTIC_VECTOR,
    REGEX,
    FIXED;

    public static ChunkingStrategyOverride fromExternalName(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "AUTO" -> AUTO;
            case "F", "FIX", "FIXED" -> FIXED;
            case "P", "PARAGRAPH", "PARAGRAPH_SEMANTIC" -> PARAGRAPH;
            case "R", "RECURSIVE" -> RECURSIVE;
            case "V", "VECTOR", "SEMANTIC_VECTOR" -> SEMANTIC_VECTOR;
            case "SMART" -> SMART;
            case "REGEX" -> REGEX;
            default -> throw new IllegalArgumentException("unsupported chunking strategy: " + value);
        };
    }
}
