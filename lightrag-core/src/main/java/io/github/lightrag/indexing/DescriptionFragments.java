package io.github.lightrag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits and combines {@code <SEP>}-joined description fragments — the stored description format upstream
 * uses for entities and relations.
 *
 * <p>Combining is the dedup pass upstream added in 2026-08 ({@code _combine_descriptions_dedup},
 * {@code operate.py:2384-2426}): stored fragments keep their order and come first, then new fragments not
 * already present. Comparison happens after {@link TextSanitizer#sanitizeForEncoding(String)} so a
 * re-extracted fragment that only differs by an illegal control character still deduplicates, and
 * fragments that sanitize to empty are dropped (they would only emit {@code <SEP><SEP>} artifacts).</p>
 */
public final class DescriptionFragments {
    public static final String SEPARATOR = "<SEP>";

    private static final Pattern SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(SEPARATOR));

    private DescriptionFragments() {
    }

    public static List<String> combine(List<String> stored, List<String> incoming) {
        var combined = new ArrayList<String>();
        var seen = new LinkedHashSet<String>();
        addSanitized(stored, combined, seen);
        addSanitized(incoming, combined, seen);
        return List.copyOf(combined);
    }

    public static List<String> split(String joined) {
        if (joined == null || joined.isEmpty()) {
            return List.of();
        }
        var fragments = new ArrayList<String>();
        for (var raw : SEPARATOR_PATTERN.split(joined, -1)) {
            var sanitized = TextSanitizer.sanitizeForEncoding(raw);
            if (!sanitized.isEmpty()) {
                fragments.add(sanitized);
            }
        }
        return List.copyOf(fragments);
    }

    private static void addSanitized(List<String> fragments, List<String> combined, LinkedHashSet<String> seen) {
        if (fragments == null) {
            return;
        }
        for (var fragment : fragments) {
            var sanitized = TextSanitizer.sanitizeForEncoding(fragment);
            if (!sanitized.isEmpty() && seen.add(sanitized)) {
                combined.add(sanitized);
            }
        }
    }
}
