package io.github.lightrag.ontology;

import java.util.Locale;
import java.util.Map;

/**
 * Property value types aligned with {@code rdfs:Datatype}.
 */
public enum OntologyPropertyType {
    STRING,
    INTEGER,
    FLOAT,
    BOOLEAN,
    DATE,
    LIST;

    private static final Map<String, OntologyPropertyType> ALIASES = Map.ofEntries(
        Map.entry("STRING", STRING),
        Map.entry("TEXT", STRING),
        Map.entry("INTEGER", INTEGER),
        Map.entry("INT", INTEGER),
        Map.entry("LONG", INTEGER),
        Map.entry("FLOAT", FLOAT),
        Map.entry("DOUBLE", FLOAT),
        Map.entry("NUMBER", FLOAT),
        Map.entry("BOOLEAN", BOOLEAN),
        Map.entry("BOOL", BOOLEAN),
        Map.entry("DATE", DATE),
        Map.entry("TIME", DATE),
        Map.entry("LIST", LIST),
        Map.entry("ARRAY", LIST)
    );

    /**
     * Normalizes a raw type string to a property type.
     *
     * @param value the raw type string; {@code null} or blank returns {@link #STRING}
     * @return the resolved property type
     * @throws IllegalArgumentException if the value is not a known type or alias
     */
    public static OntologyPropertyType normalize(String value) {
        if (value == null) {
            return STRING;
        }
        String normalized = Identity.normalizeWhitespace(value);
        if (normalized == null) {
            return STRING;
        }
        OntologyPropertyType type = ALIASES.get(normalized.toUpperCase(Locale.ROOT));
        if (type == null) {
            throw new IllegalArgumentException("Unknown ontology property type: " + value);
        }
        return type;
    }
}
