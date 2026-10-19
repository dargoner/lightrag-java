package io.github.lightrag.rdf;

import io.github.lightrag.ontology.OntologyPropertyType;

import java.util.Optional;
import java.util.Set;

/**
 * Mapping between XSD datatype IRIs and {@link OntologyPropertyType}, plus the
 * lexical values used by the {@code lg:valueType} extension term.
 *
 * <p>{@code LIST} has no XSD datatype: the exporter writes
 * {@code lg:valueType "LIST"} and omits {@code rdfs:range}.
 */
public final class RdfFormatValue {

    private static final Set<String> XSD_STRING_TYPES = Set.of(
        "string", "normalizedString", "token", "language", "anyURI", "Name",
        "NCName", "NMTOKEN", "NMTOKENS", "ID", "IDREF", "IDREFS", "ENTITY",
        "ENTITIES", "QName", "NOTATION"
    );
    private static final Set<String> XSD_INTEGER_TYPES = Set.of(
        "integer", "int", "long", "short", "byte",
        "nonNegativeInteger", "nonPositiveInteger", "negativeInteger",
        "positiveInteger", "unsignedLong", "unsignedInt", "unsignedShort",
        "unsignedByte"
    );
    private static final Set<String> XSD_FLOAT_TYPES = Set.of("float", "double", "decimal");
    private static final Set<String> XSD_DATE_TYPES = Set.of(
        "date", "dateTime", "dateTimeStamp", "time",
        "gYear", "gYearMonth", "gMonth", "gMonthDay", "gDay"
    );

    private RdfFormatValue() {}

    /**
     * Resolves an XSD datatype IRI to a property type.
     *
     * @return the mapped type, or empty when the IRI is not an XSD datatype or
     *         has no supported mapping
     */
    public static Optional<OntologyPropertyType> fromXsdDatatype(String datatypeIri) {
        if (datatypeIri == null || !datatypeIri.startsWith(RdfVocabulary.XSD_NS)) {
            return Optional.empty();
        }
        String local = datatypeIri.substring(RdfVocabulary.XSD_NS.length());
        if (XSD_STRING_TYPES.contains(local)) {
            return Optional.of(OntologyPropertyType.STRING);
        }
        if (XSD_INTEGER_TYPES.contains(local)) {
            return Optional.of(OntologyPropertyType.INTEGER);
        }
        if (XSD_FLOAT_TYPES.contains(local)) {
            return Optional.of(OntologyPropertyType.FLOAT);
        }
        if ("boolean".equals(local)) {
            return Optional.of(OntologyPropertyType.BOOLEAN);
        }
        if (XSD_DATE_TYPES.contains(local)) {
            return Optional.of(OntologyPropertyType.DATE);
        }
        return Optional.empty();
    }

    /**
     * Resolves the lexical value of an {@code lg:valueType} literal.
     *
     * <p>Accepts the canonical names and the aliases understood by
     * {@link OntologyPropertyType#normalize(String)}, as well as an XSD
     * datatype IRI.
     *
     * @return the mapped type, or empty for {@code null}/blank input
     * @throws IllegalArgumentException when the value is not a known type
     */
    public static Optional<OntologyPropertyType> fromValueType(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.strip();
        if (trimmed.startsWith(RdfVocabulary.XSD_NS)) {
            return fromXsdDatatype(trimmed);
        }
        return Optional.of(OntologyPropertyType.normalize(trimmed));
    }

    /**
     * Returns the XSD datatype IRI the exporter uses for a property type.
     *
     * @return the XSD IRI, or empty for {@link OntologyPropertyType#LIST}
     */
    public static Optional<String> toXsdDatatype(OntologyPropertyType type) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            case STRING -> Optional.of(RdfVocabulary.XSD_NS + "string");
            case INTEGER -> Optional.of(RdfVocabulary.XSD_NS + "integer");
            case FLOAT -> Optional.of(RdfVocabulary.XSD_NS + "double");
            case BOOLEAN -> Optional.of(RdfVocabulary.XSD_NS + "boolean");
            case DATE -> Optional.of(RdfVocabulary.XSD_NS + "date");
            case LIST -> Optional.empty();
        };
    }
}
