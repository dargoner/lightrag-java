package io.github.lightrag.indexing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class UnicodeCodePointChunkTextTokenizer implements ChunkTextTokenizer {
    public static final UnicodeCodePointChunkTextTokenizer INSTANCE = new UnicodeCodePointChunkTextTokenizer();

    private UnicodeCodePointChunkTextTokenizer() {
    }

    @Override
    public List<String> encode(String text) {
        var source = Objects.requireNonNull(text, "text");
        var tokens = new ArrayList<String>();
        var index = 0;
        while (index < source.length()) {
            var codePoint = source.codePointAt(index);
            tokens.add(new String(Character.toChars(codePoint)));
            index += Character.charCount(codePoint);
        }
        return List.copyOf(tokens);
    }

    @Override
    public String decode(List<String> tokens) {
        return String.join("", Objects.requireNonNull(tokens, "tokens"));
    }

    @Override
    public int count(String text) {
        return Objects.requireNonNull(text, "text").codePointCount(0, text.length());
    }
}
