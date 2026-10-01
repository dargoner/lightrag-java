package io.github.lightrag.text;

/**
 * East Asian wide code point blocks, ported from the upstream {@code query_validation.py} table.
 *
 * <p>Shared by {@link io.github.lightrag.query.QueryValidation} (CJK-weighted minimum query length) and
 * {@link io.github.lightrag.model.HeuristicTokenCounter} (token estimation).</p>
 *
 * <p>Ranges are whole Unicode blocks and must stay that way: upstream once carried a per-character
 * boundary that cut Hangul Jamo in half, leaving Korean medial and final jamo weighing 1
 * ({@code query_validation.py:35-37}).</p>
 */
public final class UnicodeWidths {
    private static final int[][] WIDE_RANGES = {
        {0x1100, 0x11FF},    // Hangul Jamo
        {0x2E80, 0x33FF},    // CJK Radicals, Kangxi, Symbols, Kana, Bopomofo, Hangul
        {0x3400, 0x4DBF},    // CJK Unified Ideographs Extension A
        {0x4E00, 0x9FFF},    // CJK Unified Ideographs
        {0xA960, 0xA97F},    // Hangul Jamo Extended-A
        {0xAC00, 0xD7FF},    // Hangul Syllables and Hangul Jamo Extended-B
        {0xF900, 0xFAFF},    // CJK Compatibility Ideographs
        {0xFE30, 0xFE4F},    // CJK Compatibility Forms
        {0xFF00, 0xFFEE},    // Halfwidth and Fullwidth Forms
        {0x16FE0, 0x16FFF},  // Ideographic Symbols and Punctuation
        {0x1AFF0, 0x1B2FF},  // Kana Extended-B/Supplement/Extended-A, Small Kana, Nushu
        {0x20000, 0x3FFFD},  // Planes 2 and 3 - every CJK extension, present and future
    };

    private UnicodeWidths() {
    }

    public static boolean isWide(int codePoint) {
        for (var range : WIDE_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) {
                return true;
            }
        }
        return false;
    }
}
