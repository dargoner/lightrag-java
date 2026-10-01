package io.github.lightrag.model;

import io.github.lightrag.text.UnicodeWidths;

/**
 * Character-class based token estimate: each CJK/Kana/Hangul code point counts as one token, other text at
 * roughly four characters per token.
 *
 * <p>This is a documented Java-only divergence from upstream, which budgets with the provider's real
 * tokenizer. The heuristic errs toward over-counting wide text (the safe direction: keep less context,
 * never overflow the provider window); plug a real tokenizer through
 * {@link io.github.lightrag.api.LightRagBuilder} when exact counts matter.</p>
 */
public final class HeuristicTokenCounter implements TokenCounter {
    private static final int NON_WIDE_CHARS_PER_TOKEN = 4;

    @Override
    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        var tokens = 0;
        var runLength = 0;
        for (var index = 0; index < text.length(); ) {
            var codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (UnicodeWidths.isWide(codePoint)) {
                tokens += ceilDiv(runLength);
                runLength = 0;
                tokens++;
            } else {
                runLength++;
            }
        }
        return tokens + ceilDiv(runLength);
    }

    private static int ceilDiv(int length) {
        return length == 0 ? 0 : (length + NON_WIDE_CHARS_PER_TOKEN - 1) / NON_WIDE_CHARS_PER_TOKEN;
    }
}
