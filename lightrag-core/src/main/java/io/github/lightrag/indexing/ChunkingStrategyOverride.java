package io.github.lightrag.indexing;

import java.util.Locale;

public enum ChunkingStrategyOverride {
    AUTO,
    SMART,
    REGEX,
    FIXED;

    public static ChunkingStrategyOverride fromExternalName(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "AUTO" -> AUTO;
            case "F", "FIX", "FIXED" -> FIXED;
            case "P", "PARAGRAPH", "PARAGRAPH_SEMANTIC", "SMART" -> SMART;
            case "REGEX" -> REGEX;
            case "R", "RECURSIVE" -> throw unsupported(value, "recursive character chunking is not implemented");
            case "V", "VECTOR", "SEMANTIC_VECTOR" -> throw unsupported(value, "vector breakpoint chunking is not implemented");
            default -> throw new IllegalArgumentException("unsupported chunking strategy: " + value);
        };
    }

    private static IllegalArgumentException unsupported(String value, String reason) {
        return new IllegalArgumentException(
            "unsupported upstream chunking strategy " + value.strip() + ": " + reason
        );
    }
}
