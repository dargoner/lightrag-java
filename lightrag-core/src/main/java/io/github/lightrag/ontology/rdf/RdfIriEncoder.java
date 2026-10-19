package io.github.lightrag.ontology.rdf;

import io.github.lightrag.ontology.Identity;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Deterministic, injective IRI allocation for ontology vocabulary terms.
 *
 * <p>Every generated IRI is built from path segments encoded with strict
 * percent-encoding over the UTF-8 bytes of the canonical
 * {@link Identity#identityId(String)} value. Only the RFC 3986 unreserved
 * characters {@code A-Z a-z 0-9 - _ ~} are left unescaped; every other byte is
 * written as {@code %HH} with upper-case hexadecimal digits. In particular
 * {@code '.'} is encoded as {@code %2E} so that {@code .} and {@code ..} cannot
 * be collapsed as path segments. The encoding is injective and there is no
 * collision-hash fallback.
 *
 * <p>The layered layouts are:
 *
 * <pre>
 * class/{entity}
 * relation/{relation}
 * attribute/entity/{owner}/{property}
 * attribute/relation/{owner}/{property}
 * relationPattern/{relation}/{source}/{target}
 * shapes/class/{owner}/shape
 * shapes/class/{owner}/property/{property}
 * </pre>
 */
public final class RdfIriEncoder {

    /** Maximum IRI length in unescaped UTF-8 bytes. */
    public static final int MAX_IRI_BYTES = 8 * 1024;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final String vocabularyBase;
    private final String shapesBase;

    /**
     * Creates an encoder whose SHACL shapes live below
     * {@code vocabularyBase + "shapes/"}.
     */
    public RdfIriEncoder(String vocabularyBase) {
        this(vocabularyBase, vocabularyBase == null ? null : vocabularyBase + "shapes/");
    }

    /**
     * Creates an encoder for the given absolute namespaces.
     *
     * @param vocabularyBase absolute namespace ending with {@code '/'}
     * @param shapesBase     absolute namespace ending with {@code '/'}
     * @throws IllegalArgumentException if a namespace is relative or lacks the
     *                                  trailing slash
     */
    public RdfIriEncoder(String vocabularyBase, String shapesBase) {
        this.vocabularyBase = requireBaseIri(vocabularyBase, "vocabularyBase");
        this.shapesBase = requireBaseIri(shapesBase, "shapesBase");
    }

    public String vocabularyBase() {
        return vocabularyBase;
    }

    public String shapesBase() {
        return shapesBase;
    }

    /** {@code class/{entity}}. */
    public String entityClass(String label) {
        return checkLength(vocabularyBase + "class/" + segment(label));
    }

    /** {@code relation/{relation}}. */
    public String relationProperty(String label) {
        return checkLength(vocabularyBase + "relation/" + segment(label));
    }

    /** {@code attribute/entity/{owner}/{property}}. */
    public String entityAttribute(String ownerLabel, String propertyName) {
        return checkLength(
            vocabularyBase + "attribute/entity/" + segment(ownerLabel) + "/" + segment(propertyName)
        );
    }

    /** {@code attribute/relation/{owner}/{property}}. */
    public String relationAttribute(String ownerLabel, String propertyName) {
        return checkLength(
            vocabularyBase + "attribute/relation/" + segment(ownerLabel) + "/" + segment(propertyName)
        );
    }

    /** {@code class/{owner}/shape} below the SHACL shapes base. */
    public String shaclNode(String ownerLabel) {
        return checkLength(shapesBase + "class/" + segment(ownerLabel) + "/shape");
    }

    /** {@code class/{owner}/property/{property}} below the SHACL shapes base. */
    public String shaclPropertyNode(String ownerLabel, String propertyName) {
        return checkLength(
            shapesBase + "class/" + segment(ownerLabel) + "/property/" + segment(propertyName)
        );
    }

    /** Stable node IRI for a concrete relation pattern. */
    public String relationPatternNode(String relationLabel, String sourceLabel, String targetLabel) {
        return checkLength(
            vocabularyBase + "relationPattern/" + segment(relationLabel)
                + "/" + segment(sourceLabel) + "/" + segment(targetLabel)
        );
    }

    /** Stable node IRI describing the source binding of {@code subjectIri}. */
    public String sourceBindingNode(String subjectIri) {
        String subject = requireAbsoluteIri(subjectIri, "subjectIri");
        return checkLength(subject + "/sourceBinding");
    }

    /**
     * Strictly percent-encodes a raw path segment over its UTF-8 bytes.
     *
     * <p>This method does not apply identity normalization; it is the raw
     * injective encoding primitive used by the layered factory methods.
     */
    public static String percentEncode(String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("segment must not be null");
        }
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("segment must not be empty");
        }
        requireWellFormedUnicode(segment, "segment");

        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int value = raw & 0xFF;
            if (isUnreserved(value)) {
                result.append((char) value);
            } else {
                result.append('%').append(HEX[value >>> 4]).append(HEX[value & 0x0F]);
            }
        }
        return result.toString();
    }

    /** Alias for {@link #percentEncode(String)}. */
    public static String encodeSegment(String segment) {
        return percentEncode(segment);
    }

    /** Returns the unescaped UTF-8 byte length of {@code value}. */
    public static int utf8Length(String value) {
        Objects.requireNonNull(value, "value");
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Validates an absolute IRI for safe use inside a Turtle IRIREF.
     *
     * @return the unchanged value
     * @throws IllegalArgumentException if the IRI is missing a scheme, contains
     *                                  whitespace/control characters, contains
     *                                  Turtle-forbidden IRIREF characters, or
     *                                  contains a malformed percent escape
     */
    public static String requireAbsoluteIri(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        requireWellFormedUnicode(value, field);

        int colon = value.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException(field + " must be an absolute IRI: " + value);
        }
        char first = value.charAt(0);
        if (!isAsciiLetter(first)) {
            throw new IllegalArgumentException(field + " has an invalid scheme: " + value);
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
                    field + " contains a character forbidden in a Turtle IRIREF: " + value
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

    /** Validates an absolute base namespace that must end with {@code '/'}. */
    public static String requireBaseIri(String value, String field) {
        String iri = requireAbsoluteIri(value, field);
        if (!iri.endsWith("/")) {
            throw new IllegalArgumentException(field + " must end with '/': " + iri);
        }
        return iri;
    }

    /** Rejects unpaired UTF-16 surrogates so the UTF-8 encoding is injective. */
    public static void requireWellFormedUnicode(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
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

    private static String segment(String label) {
        if (label == null) {
            throw new IllegalArgumentException("identity label must not be null");
        }
        return percentEncode(Identity.identityId(label));
    }

    private static String checkLength(String iri) {
        int length = utf8Length(iri);
        if (length > MAX_IRI_BYTES) {
            throw new OntologyRdfExportException(
                "Generated IRI exceeds " + MAX_IRI_BYTES + " UTF-8 bytes ("
                    + length + "): " + abbreviate(iri)
            );
        }
        return iri;
    }

    private static String abbreviate(String value) {
        return value.length() <= 128 ? value : value.substring(0, 128) + "...";
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
            || (value >= 'a' && value <= 'z')
            || (value >= '0' && value <= '9')
            || value == '-' || value == '_' || value == '~';
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isAsciiDigit(c)
            || (c >= 'A' && c <= 'F')
            || (c >= 'a' && c <= 'f');
    }
}
