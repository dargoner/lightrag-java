package io.github.lightragjava.indexing;

import java.util.Objects;
import java.util.regex.Pattern;

public record RegexChunkRule(String pattern) {
    public RegexChunkRule {
        Objects.requireNonNull(pattern, "pattern");
        pattern = pattern.strip();
        if (pattern.isEmpty()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
    }

    Pattern compile() {
        return Pattern.compile(pattern, Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
