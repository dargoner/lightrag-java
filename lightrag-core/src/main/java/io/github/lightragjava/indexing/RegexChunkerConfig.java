package io.github.lightragjava.indexing;

import java.util.List;
import java.util.Objects;

public record RegexChunkerConfig(List<RegexChunkRule> rules) {
    public RegexChunkerConfig {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public static RegexChunkerConfig empty() {
        return new RegexChunkerConfig(List.of());
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }
}
