package io.github.lightrag.query;

import io.github.lightrag.text.UnicodeWidths;

/**
 * Upstream-aligned query text validation ({@code query_validation.py}): every mode rejects empty text;
 * RAG retrieval modes additionally require a CJK-weighted minimum length.
 */
public final class QueryValidation {
    public static final int MIN_RAG_QUERY_WEIGHT = 3;
    private static final String TOO_SHORT_MESSAGE =
        "RAG query is too short. Enter at least 3 English characters or an equivalent "
            + "combination where each Chinese, Japanese or Korean character counts as 2.";

    private QueryValidation() {
    }

    public static void validateNotEmpty(String query) {
        if (query == null || query.strip().isEmpty()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }
    }

    public static void validateRagQuery(String query) {
        validateNotEmpty(query);
        if (!meetsMinWeight(query)) {
            throw new IllegalArgumentException(TOO_SHORT_MESSAGE);
        }
    }

    /** Short-circuits as soon as the threshold is reached (upstream: meets_min_rag_query_weight). */
    public static boolean meetsMinWeight(String query) {
        var stripped = query.strip();
        var weight = 0;
        for (var index = 0; index < stripped.length(); ) {
            var codePoint = stripped.codePointAt(index);
            index += Character.charCount(codePoint);
            weight += UnicodeWidths.isWide(codePoint) ? 2 : 1;
            if (weight >= MIN_RAG_QUERY_WEIGHT) {
                return true;
            }
        }
        return false;
    }
}
