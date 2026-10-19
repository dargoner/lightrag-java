package io.github.lightrag.ontology;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cross-repository identity function for ontology labels.
 *
 * <p>Two labels are considered the same identity when they share the same
 * {@link #identityId(String)}: trimmed, whitespace-collapsed, NFC-normalized,
 * and lower-cased. The original display label is preserved on each record.
 */
public final class Identity {

    /**
     * Matches ASCII control whitespace (tab, newline, etc.) plus Unicode space
     * characters such as non-breaking and narrow spaces that {@code \\s} and
     * {@link String#strip()} do not collapse.
     */
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{javaSpaceChar}]+", Pattern.UNICODE_CHARACTER_CLASS);

    private Identity() {}

    /**
     * Returns the canonical identity key for a label.
     *
     * @param label the raw label; must not be blank
     * @return the normalized identity key
     * @throws IllegalArgumentException if the label is blank after normalization
     */
    public static String identityId(String label) {
        Objects.requireNonNull(label, "label");
        String normalized = normalizeWhitespace(label);
        if (normalized == null) {
            throw new IllegalArgumentException("label must not be blank");
        }
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);
        normalized = normalized.toLowerCase(Locale.ROOT);
        return normalized;
    }

    /**
     * Collapses Unicode whitespace and trims the label.
     *
     * @param label the raw label
     * @return the collapsed and trimmed label, or {@code null} if the result is blank
     */
    static String normalizeWhitespace(String label) {
        if (label == null) {
            return null;
        }
        String collapsed = WHITESPACE.matcher(label).replaceAll(" ").strip();
        if (collapsed.isEmpty()) {
            return null;
        }
        return Normalizer.normalize(collapsed, Normalizer.Form.NFC);
    }

    /**
     * Returns the identity without whitespace, underscores, or hyphens so
     * multi-word kernel names compare equal regardless of spelling: both
     * {@code "relationship description"} and
     * {@code "relationship_description"} collapse to
     * {@code "relationshipdescription"}.
     */
    static String separatorFree(String identity) {
        StringBuilder compact = new StringBuilder(identity.length());
        for (int index = 0; index < identity.length(); index++) {
            char character = identity.charAt(index);
            if (Character.isWhitespace(character) || character == '_' || character == '-') {
                continue;
            }
            compact.append(character);
        }
        return compact.toString();
    }
}
