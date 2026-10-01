package io.github.lightrag.text;

/**
 * East Asian wide / fullwidth code point classification used for token estimation.
 *
 * <p>Wide code points (CJK ideographs, Kana, Hangul, fullwidth forms, CJK punctuation) are counted as one
 * token each by {@link io.github.lightrag.model.HeuristicTokenCounter}, while other text is estimated at
 * roughly four characters per token.</p>
 */
public final class UnicodeWidths {
    private UnicodeWidths() {
    }

    public static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
            || (codePoint >= 0x2E80 && codePoint <= 0x303E)
            || (codePoint >= 0x3041 && codePoint <= 0x33FF)
            || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
            || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
            || (codePoint >= 0xA000 && codePoint <= 0xA4CF)
            || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
            || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
            || (codePoint >= 0xFE10 && codePoint <= 0xFE19)
            || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
            || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
            || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
            || (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
            || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }
}
