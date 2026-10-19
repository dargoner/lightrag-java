package io.github.lightrag.ontology.rdf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Minimal, dependency-free Turtle writer with deterministic output.
 *
 * <p>Prefix declarations are emitted in prefix-name order, comments in
 * lexicographic order, and triples as one statement per line in lexicographic
 * order. Identical triples are deduplicated. The writer never emits generated
 * timestamps or random blank-node identifiers.
 *
 * <p>Literals escape {@code \\}, {@code "}, line feed, carriage return, tab,
 * backspace, form feed, and remaining ISO control characters as Unicode escape
 * sequences.
 * Language-tagged and datatype literals are supported; XSD datatypes use the
 * compact {@code ^^xsd:local} form.
 *
 * <p>This class is mutable and not thread-safe; call {@link #render()} when the
 * document is complete.
 */
public final class TurtleWriter {

    /** XSD namespace used for compact datatype literals. */
    public static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema#";

    private static final Pattern PREFIX_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern PREFIXED_NAME = Pattern.compile(
        "(?:a|[A-Za-z_][A-Za-z0-9_.-]*:[A-Za-z_][A-Za-z0-9_.-]*)"
    );
    private static final Pattern BLANK_NODE_LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z]+(?:-[A-Za-z0-9]+)*");
    private static final Pattern SIMPLE_LOCAL_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    private final Map<String, String> prefixes = new LinkedHashMap<>();
    private final TreeSet<String> comments = new TreeSet<>();
    private final TreeSet<String> triples = new TreeSet<>();

    public TurtleWriter() {}

    /**
     * Registers a prefix. Re-registering the same name with the same IRI is
     * idempotent; a different IRI is rejected.
     */
    public TurtleWriter prefix(String name, String iri) {
        if (name == null || !PREFIX_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid Turtle prefix name: " + name);
        }
        String namespace = RdfIriEncoder.requireAbsoluteIri(iri, "prefix IRI");
        String previous = prefixes.putIfAbsent(name, namespace);
        if (previous != null && !previous.equals(namespace)) {
            throw new IllegalArgumentException(
                "Prefix '" + name + "' is already bound to " + previous
            );
        }
        return this;
    }

    /** Alias for {@link #prefix(String, String)}. */
    public TurtleWriter addPrefix(String name, String iri) {
        return prefix(name, iri);
    }

    /** Adds a deterministic comment line. */
    public TurtleWriter comment(String comment) {
        Objects.requireNonNull(comment, "comment");
        StringBuilder sanitized = new StringBuilder(comment.length());
        for (int i = 0; i < comment.length(); i++) {
            char c = comment.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || Character.isISOControl(c)) {
                sanitized.append(' ');
            } else {
                sanitized.append(c);
            }
        }
        String line = sanitized.toString().strip();
        if (!line.isEmpty()) {
            comments.add(line);
        }
        return this;
    }

    /** Alias for {@link #comment(String)}. */
    public TurtleWriter addComment(String comment) {
        return comment(comment);
    }

    /** Adds a triple whose terms may be IRIs, prefixed names, literals, blank nodes, or lists. */
    public TurtleWriter triple(Term subject, Term predicate, Term object) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(object, "object");

        if (!(subject instanceof Iri
            || subject instanceof PrefixedName
            || subject instanceof BlankNode
            || subject instanceof RdfList)) {
            throw new IllegalArgumentException("Turtle subject must be an IRI, prefixed name, blank node, or list");
        }
        if (!(predicate instanceof Iri || predicate instanceof PrefixedName)) {
            throw new IllegalArgumentException("Turtle predicate must be an IRI or prefixed name");
        }

        triples.add(subject.render() + " " + predicate.render() + " " + object.render() + " .");
        return this;
    }

    /** Adds a triple with full IRIs for subject, predicate, and object. */
    public TurtleWriter iriTriple(String subjectIri, String predicateIri, String objectIri) {
        return triple(iri(subjectIri), iri(predicateIri), iri(objectIri));
    }

    /** Alias for {@link #triple(Term, Term, Term)}. */
    public TurtleWriter addTriple(Term subject, Term predicate, Term object) {
        return triple(subject, predicate, object);
    }

    /** Emits {@code subject a typeIri}. */
    public TurtleWriter type(String subjectIri, String typeIri) {
        return triple(iri(subjectIri), prefixed("a"), iri(typeIri));
    }

    /** Emits a plain string literal. */
    public TurtleWriter literal(String subjectIri, Term predicate, String lexical) {
        return triple(iri(subjectIri), predicate, literal(lexical));
    }

    /** Emits a plain string literal with a full-IRI predicate. */
    public TurtleWriter literal(String subjectIri, String predicateIri, String lexical) {
        return literal(subjectIri, iri(predicateIri), lexical);
    }

    /** Emits a language-tagged literal. */
    public TurtleWriter langLiteral(String subjectIri, Term predicate, String lexical, String languageTag) {
        return triple(iri(subjectIri), predicate, langLiteral(lexical, languageTag));
    }

    /** Emits a datatype literal. */
    public TurtleWriter typedLiteral(
        String subjectIri,
        Term predicate,
        String lexical,
        String datatypeIri
    ) {
        return triple(iri(subjectIri), predicate, typedLiteral(lexical, datatypeIri));
    }

    /** Renders the complete Turtle document. */
    public String render() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(prefixes).entrySet()) {
            out.append("@prefix ").append(entry.getKey()).append(": <")
                .append(entry.getValue()).append("> .\n");
        }
        if (!prefixes.isEmpty()) {
            out.append('\n');
        }
        for (String comment : comments) {
            out.append("# ").append(comment).append('\n');
        }
        if (!comments.isEmpty()) {
            out.append('\n');
        }
        for (String triple : triples) {
            out.append(triple).append('\n');
        }
        return out.toString();
    }

    /** Alias for {@link #render()}. */
    public String toTurtle() {
        return render();
    }

    /** Creates an IRI term. */
    public static Term iri(String value) {
        return new Iri(value);
    }

    /** Creates a prefixed-name term such as {@code rdfs:label} or {@code a}. */
    public static Term prefixed(String value) {
        return new PrefixedName(value);
    }

    /** Creates a plain literal term. */
    public static Term literal(String lexical) {
        return new Literal(lexical, null, null);
    }

    /** Creates a language-tagged literal term. */
    public static Term langLiteral(String lexical, String languageTag) {
        return new Literal(lexical, languageTag, null);
    }

    /** Creates a datatype literal term. */
    public static Term typedLiteral(String lexical, String datatypeIri) {
        return new Literal(lexical, null, datatypeIri);
    }

    /** Creates a blank-node term with a stable label. */
    public static Term blankNode(String label) {
        return new BlankNode(label);
    }

    /** Creates an RDF list term. */
    public static Term list(Term... items) {
        return new RdfList(List.of(items));
    }

    /** Creates an RDF list term from an existing list. */
    public static Term list(List<Term> items) {
        return new RdfList(items);
    }

    /**
     * Escapes a lexical value for a double-quoted Turtle literal. The input is
     * measured before escaping, matching the importer's UTF-8 byte limit.
     */
    public static String escapeLiteral(String value) {
        Objects.requireNonNull(value, "value");
        RdfIriEncoder.requireWellFormedUnicode(value, "literal");
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (Character.isISOControl(c)) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int padding = hex.length(); padding < 4; padding++) {
                            escaped.append('0');
                        }
                        escaped.append(hex.toUpperCase(java.util.Locale.ROOT));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** A Turtle term. */
    public interface Term {
        /** Renders this term as Turtle syntax. */
        String render();
    }

    /** An absolute IRI rendered as {@code <iri>}. */
    public record Iri(String value) implements Term {
        public Iri {
            RdfIriEncoder.requireAbsoluteIri(value, "IRI");
        }

        @Override
        public String render() {
            return "<" + value + ">";
        }
    }

    /** A prefixed name such as {@code rdfs:label}; {@code a} is accepted. */
    public record PrefixedName(String value) implements Term {
        public PrefixedName {
            if (value == null || !PREFIXED_NAME.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid Turtle prefixed name: " + value);
            }
        }

        @Override
        public String render() {
            return value;
        }
    }

    /**
     * A literal with optional language tag or datatype IRI. Exactly one of
     * {@code languageTag} and {@code datatypeIri} may be non-null.
     */
    public record Literal(String lexical, String languageTag, String datatypeIri) implements Term {
        public Literal {
            Objects.requireNonNull(lexical, "lexical");
            RdfIriEncoder.requireWellFormedUnicode(lexical, "literal");
            if (languageTag != null && datatypeIri != null) {
                throw new IllegalArgumentException("A literal cannot have both a language tag and a datatype");
            }
            if (languageTag != null && !LANGUAGE_TAG.matcher(languageTag).matches()) {
                throw new IllegalArgumentException("Invalid language tag: " + languageTag);
            }
            if (datatypeIri != null) {
                RdfIriEncoder.requireAbsoluteIri(datatypeIri, "datatypeIri");
            }
        }

        @Override
        public String render() {
            String escaped = escapeLiteral(lexical);
            StringBuilder rendered = new StringBuilder(escaped.length() + 16);
            rendered.append('"').append(escaped).append('"');
            if (languageTag != null) {
                rendered.append('@').append(languageTag);
            } else if (datatypeIri != null) {
                if (datatypeIri.startsWith(XSD_NAMESPACE)) {
                    String local = datatypeIri.substring(XSD_NAMESPACE.length());
                    if (SIMPLE_LOCAL_NAME.matcher(local).matches()) {
                        rendered.append("^^xsd:").append(local);
                        return rendered.toString();
                    }
                }
                rendered.append("^^<").append(datatypeIri).append('>');
            }
            return rendered.toString();
        }
    }

    /** A blank node rendered as {@code _:label}. */
    public record BlankNode(String label) implements Term {
        public BlankNode {
            if (label == null || !BLANK_NODE_LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Invalid blank node label: " + label);
            }
        }

        @Override
        public String render() {
            return "_:" + label;
        }
    }

    /** An RDF list rendered as {@code ( item ... )}. */
    public record RdfList(List<Term> items) implements Term {
        public RdfList {
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public String render() {
            if (items.isEmpty()) {
                return "()";
            }
            StringBuilder rendered = new StringBuilder("( ");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    rendered.append(' ');
                }
                rendered.append(items.get(i).render());
            }
            return rendered.append(" )").toString();
        }
    }
}
