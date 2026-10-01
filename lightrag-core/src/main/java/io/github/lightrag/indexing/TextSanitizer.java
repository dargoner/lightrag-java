package io.github.lightrag.indexing;

/**
 * Sanitizes text before it is persisted or embedded: strips XML-illegal control characters and unpaired
 * surrogates, then trims.
 *
 * <p>Port of upstream {@code sanitize_text_for_encoding} (the graph payload paths that need it):
 * descriptions read back from stored graph rows, or produced by an LLM summary, are not guaranteed to be
 * XML-safe, and a bad code point would break GraphML/XML serialization downstream.</p>
 *
 * <p>LaTeX escape damage is repaired first ({@link LatexEscapeRepair}): stripping control characters is
 * exactly what would maim a decoded {@code \frac} -- the form feed must become a backslash before it is
 * dropped. Isolated control characters the repair leaves alone are then removed as before.</p>
 */
public final class TextSanitizer {
    private TextSanitizer() {
    }

    public static String sanitizeForEncoding(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        var repaired = LatexEscapeRepair.repair(text);
        var builder = new StringBuilder(repaired.length());
        for (var index = 0; index < repaired.length(); ) {
            var current = repaired.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 < repaired.length() && Character.isLowSurrogate(repaired.charAt(index + 1))) {
                    builder.append(current).append(repaired.charAt(index + 1));
                    index += 2;
                    continue;
                }
                index++;
                continue;
            }
            if (Character.isLowSurrogate(current)) {
                index++;
                continue;
            }
            if ((current < 0x20 && current != '\t' && current != '\n' && current != '\r') || current == 0x7F) {
                index++;
                continue;
            }
            builder.append(current);
            index++;
        }
        return builder.toString().strip();
    }
}
