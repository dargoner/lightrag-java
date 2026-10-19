package io.github.lightrag.rdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * IRI validation and local-name helpers shared by the importer.
 *
 * <p>The rules mirror the exporter contract: absolute IRIs only, no whitespace
 * or characters forbidden inside a Turtle IRIREF, well-formed Unicode, and
 * bases ending with {@code '/'}.
 */
final class RdfIriSupport {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private RdfIriSupport() {}

    static String requireAbsoluteIri(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        requireWellFormedUnicode(value, field);

        int colon = value.indexOf(':');
        if (colon <= 0 || !isAsciiLetter(value.charAt(0))) {
            throw new IllegalArgumentException(field + " must be an absolute IRI: " + value);
        }
        for (int i = 1; i < colon; i++) {
            char c = value.charAt(i);
            if (!isAsciiLetter(c) && !isAsciiDigit(c) && c != '+' && c != '-' && c != '.') {
                throw new IllegalArgumentException(field + " has an invalid scheme: " + value);
            }
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || Character.isISOControl(c)
                || c == '<' || c == '>' || c == '"'
                || c == '{' || c == '}' || c == '|'
                || c == '^' || c == '`' || c == '\\') {
                throw new IllegalArgumentException(
                    field + " contains a character forbidden in an IRI: " + value
                );
            }
            if (c == '%') {
                if (i + 2 >= value.length()
                    || !isHexDigit(value.charAt(i + 1))
                    || !isHexDigit(value.charAt(i + 2))) {
                    throw new IllegalArgumentException(field + " contains a malformed percent escape: " + value);
                }
                i += 2;
            }
        }
        return value;
    }

    static String requireExtensionNamespace(String value) {
        String iri = requireAbsoluteIri(value, "extensionNamespace");
        if (!iri.endsWith("/") && !iri.endsWith("#")) {
            throw new IllegalArgumentException(
                "extensionNamespace must end with '/' or '#': " + iri
            );
        }
        return iri;
    }

    /** Returns the namespace part of an IRI, including the final {@code /}, {@code #} or {@code :}. */
    static String namespaceOf(String iri) {
        Objects.requireNonNull(iri, "iri");
        int split = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        if (split < 0) {
            split = iri.lastIndexOf(':');
        }
        return split < 0 ? iri : iri.substring(0, split + 1);
    }

    /** Returns the local name of an IRI (the part after the final separator). */
    static String localName(String iri) {
        Objects.requireNonNull(iri, "iri");
        int split = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        if (split < 0) {
            split = iri.lastIndexOf(':');
        }
        return split < 0 ? iri : iri.substring(split + 1);
    }

    /**
     * Percent-decodes a raw IRI path segment as UTF-8. Unlike
     * {@link java.net.URLDecoder}, {@code '+'} is preserved.
     */
    static String percentDecode(String segment) {
        Objects.requireNonNull(segment, "segment");
        StringBuilder result = new StringBuilder(segment.length());
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int i = 0;
        while (i < segment.length()) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()
                && isHexDigit(segment.charAt(i + 1))
                && isHexDigit(segment.charAt(i + 2))) {
                pending.write((hexValue(segment.charAt(i + 1)) << 4) | hexValue(segment.charAt(i + 2)));
                i += 3;
            } else {
                if (pending.size() > 0) {
                    result.append(pending.toString(StandardCharsets.UTF_8));
                    pending.reset();
                }
                result.append(c);
                i++;
            }
        }
        if (pending.size() > 0) {
            result.append(pending.toString(StandardCharsets.UTF_8));
        }
        return result.toString();
    }

    /** Returns the number of UTF-8 bytes of a string without allocating the encoded form. */
    static long utf8Length(String value) {
        Objects.requireNonNull(value, "value");
        long bytes = 0;
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (codePoint < 0x80) {
                bytes += 1;
            } else if (codePoint < 0x800) {
                bytes += 2;
            } else if (codePoint < 0x10000) {
                bytes += 3;
            } else {
                bytes += 4;
            }
            i += Character.charCount(codePoint);
        }
        return bytes;
    }

    /** True when the IRI belongs to RDF, RDFS, OWL, XSD or SHACL. */
    static boolean isStandardVocabulary(String iri) {
        return iri.startsWith(RdfVocabulary.RDF_NS)
            || iri.startsWith(RdfVocabulary.RDFS_NS)
            || iri.startsWith(RdfVocabulary.OWL_NS)
            || iri.startsWith(RdfVocabulary.XSD_NS)
            || iri.startsWith(RdfVocabulary.SH_NS);
    }

    /** True when the value is an absolute remote URL that must never be dereferenced. */
    static boolean isRemoteUrl(String value) {
        String scheme = schemeOf(value);
        return scheme != null
            && !scheme.equals("file")
            && !scheme.equals("jar")
            && !scheme.equals("classpath");
    }

    static String schemeOf(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = value.substring(0, colon);
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            boolean valid = isAsciiLetter(c) || (i > 0 && (isAsciiDigit(c) || c == '+' || c == '-' || c == '.'));
            if (!valid) {
                return null;
            }
        }
        return scheme.toLowerCase(Locale.ROOT);
    }

    static void requireWellFormedUnicode(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isAsciiDigit(c) || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return c - 'a' + 10;
    }
}
