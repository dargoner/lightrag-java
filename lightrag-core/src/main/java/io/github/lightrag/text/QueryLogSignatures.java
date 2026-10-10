package io.github.lightrag.text;

import java.util.Collection;

/**
 * Query log signatures for retrieval logs: emit length and a stable hash instead of the raw
 * query text, so INFO/WARN logs never carry full user queries.
 */
public final class QueryLogSignatures {

    private QueryLogSignatures() {
    }

    /**
     * Signature for a single text: {@code len=<stripped length>,hash=<hex string hash>}.
     * Null or blank input yields {@code len=0}.
     */
    public static String of(String text) {
        if (text == null || text.isBlank()) {
            return "len=0";
        }
        String stripped = text.strip();
        return "len=" + stripped.length() + ",hash=" + Integer.toHexString(stripped.hashCode());
    }

    /**
     * Entry count for keyword collections; a null collection yields 0. Keyword text may reproduce
     * the user query verbatim (fallback extraction copies the query), so logs must never carry the
     * list itself.
     */
    public static int count(Collection<String> keywords) {
        return keywords == null ? 0 : keywords.size();
    }
}
