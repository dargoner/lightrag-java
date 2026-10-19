package io.github.lightrag.rdf;

import io.github.lightrag.ontology.OntologyPropertyType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdfFormatValueTest {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

    @Test
    void mapsXsdDatatypesToPropertyTypes() {
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "string"))
            .contains(OntologyPropertyType.STRING);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "normalizedString"))
            .contains(OntologyPropertyType.STRING);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "integer"))
            .contains(OntologyPropertyType.INTEGER);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "unsignedInt"))
            .contains(OntologyPropertyType.INTEGER);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "double"))
            .contains(OntologyPropertyType.FLOAT);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "decimal"))
            .contains(OntologyPropertyType.FLOAT);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "boolean"))
            .contains(OntologyPropertyType.BOOLEAN);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "date"))
            .contains(OntologyPropertyType.DATE);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "dateTime"))
            .contains(OntologyPropertyType.DATE);
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "gYear"))
            .contains(OntologyPropertyType.DATE);
    }

    @Test
    void rejectsUnmappedOrForeignDatatypes() {
        assertThat(RdfFormatValue.fromXsdDatatype(XSD + "duration")).isEmpty();
        assertThat(RdfFormatValue.fromXsdDatatype("https://example.com/type")).isEmpty();
        assertThat(RdfFormatValue.fromXsdDatatype(null)).isEmpty();
    }

    @Test
    void mapsValueTypeAliasesAndXsdIris() {
        assertThat(RdfFormatValue.fromValueType("text")).contains(OntologyPropertyType.STRING);
        assertThat(RdfFormatValue.fromValueType("INTEGER")).contains(OntologyPropertyType.INTEGER);
        assertThat(RdfFormatValue.fromValueType("long")).contains(OntologyPropertyType.INTEGER);
        assertThat(RdfFormatValue.fromValueType("number")).contains(OntologyPropertyType.FLOAT);
        assertThat(RdfFormatValue.fromValueType("bool")).contains(OntologyPropertyType.BOOLEAN);
        assertThat(RdfFormatValue.fromValueType("time")).contains(OntologyPropertyType.DATE);
        assertThat(RdfFormatValue.fromValueType("array")).contains(OntologyPropertyType.LIST);
        assertThat(RdfFormatValue.fromValueType(XSD + "integer"))
            .contains(OntologyPropertyType.INTEGER);
        assertThat(RdfFormatValue.fromValueType(null)).isEmpty();
        assertThat(RdfFormatValue.fromValueType("  ")).isEmpty();
    }

    @Test
    void rejectsUnknownValueTypeLexicalValues() {
        assertThatThrownBy(() -> RdfFormatValue.fromValueType("currency"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown ontology property type");
    }

    @Test
    void mapsPropertyTypesToExporterDatatypes() {
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.STRING))
            .contains(XSD + "string");
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.INTEGER))
            .contains(XSD + "integer");
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.FLOAT))
            .contains(XSD + "double");
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.BOOLEAN))
            .contains(XSD + "boolean");
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.DATE))
            .contains(XSD + "date");
        assertThat(RdfFormatValue.toXsdDatatype(OntologyPropertyType.LIST)).isEmpty();
        assertThat(RdfFormatValue.toXsdDatatype(null)).isEmpty();
    }
}
