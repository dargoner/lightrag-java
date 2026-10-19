package io.github.lightrag.rdf;

import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OntologyRdfImportConflictTest {

    private static final String HEAD = """
        @prefix : <https://example.com/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix lg: <https://example.com/lg#> .

        :Person a owl:Class ; rdfs:label "Person" .
        :Org a owl:Class ; rdfs:label "Org" .
        """;

    private static String doc(String body) {
        return HEAD + body;
    }

    @Test
    void identicalDeclarationsAreDeduplicated() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :n1 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                rdfs:range xsd:string .
            :n2 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                rdfs:range xsd:string .
            """));

        assertThat(personProperties(result))
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("name", OntologyPropertyType.STRING));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void differingDisplayNameFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :n1 a owl:DatatypeProperty ; rdfs:label "Name" ; lg:owner :Person .
            :n2 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("display name");
    }

    @Test
    void differingValueTypeFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :n1 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                rdfs:range xsd:string .
            :n2 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                rdfs:range xsd:integer .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("valueType");
    }

    @Test
    void differingDescriptionFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :n1 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                rdfs:comment "first" .
            :n2 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("description");
    }

    @Test
    void differingSourceBindingFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :n1 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person .
            :n2 a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
                lg:sourceBinding [
                    a lg:SourceBinding ; lg:tableName "person" ; lg:column "name"
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("sourceBinding");
    }

    @Test
    void sameIriWithDifferentKindsFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:ObjectProperty, owl:DatatypeProperty ; rdfs:label "p" ;
                lg:owner :Person .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("declared as both");
    }

    @Test
    void sameIriWithMultipleOwnersFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person, :Org .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("multiple lg:owner");
    }

    @Test
    void propertyWithoutOwnerOrDomainIsSkippedWithAWarning() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" .
            """));

        assertThat(personProperties(result)).isEmpty();
        assertThat(result.ontology().entityTypes().get(1).properties()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("neither lg:owner nor rdfs:domain"));
    }

    @Test
    void ownerAndDomainConflictFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                lg:owner :Person ; rdfs:domain :Org .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("declares lg:owner");
    }

    @Test
    void unionDomainExpandsToOnePropertyPerNamedClass() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain [ owl:unionOf ( :Person :Org ) ] .
            """));

        assertThat(personProperties(result))
            .extracting(OntologyProperty::name)
            .containsExactly("p");
        assertThat(result.ontology().entityTypes().get(0).properties())
            .extracting(OntologyProperty::name)
            .containsExactly("p");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void unionDomainWithExplicitOwnerUsesOwnerAndWarns() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                rdfs:domain [ owl:unionOf ( :Person :Org ) ] .
            """));

        assertThat(personProperties(result))
            .extracting(OntologyProperty::name)
            .containsExactly("p");
        assertThat(result.ontology().entityTypes().get(0).properties()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("lg:owner takes precedence"));
    }

    @Test
    void unionDomainWithAnonymousMemberIsSkipped() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain [ owl:unionOf ( :Person [ a owl:Restriction ] ) ] .
            """));

        assertThat(personProperties(result)).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("anonymous or nested"));
    }

    @Test
    void unionDomainWithUndeclaredMemberIsSkipped() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain [ owl:unionOf ( :Person :Missing ) ] .
            """));

        assertThat(personProperties(result)).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("undeclared class"));
    }

    @Test
    void cyclicUnionIsSkippedWithAWarning() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain [ owl:unionOf _:l1 ] .
            _:l1 rdf:first :Person ; rdf:rest _:l2 .
            _:l2 rdf:first :Org ; rdf:rest _:l1 .
            """));

        assertThat(personProperties(result)).isEmpty();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("Cycle detected"));
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("incomplete or cyclic"));
    }

    @Test
    void relationAttributeRequiresExactlyOneOwner() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :since a lg:RelationAttribute ; lg:attributeName "since" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("exactly one lg:owner");
    }

    @Test
    void relationAttributeOwnerMustBeAnObjectProperty() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :since a lg:RelationAttribute ; lg:owner :Person ;
                lg:attributeName "since" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("not a declared owl:ObjectProperty");
    }

    @Test
    void lgValueTypeTakesPrecedenceOverRdfsRange() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:valueType "integer" ; rdfs:range xsd:string .
            """));

        assertThat(personProperties(result).get(0).type())
            .isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void invalidValueTypeFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:valueType "currency" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("unsupported lg:valueType");
    }

    @Test
    void conflictingValueTypesFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:valueType "integer", "string" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting lg:valueType");
    }

    @Test
    void unsupportedRangeFallsBackToStringWithAWarning() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                rdfs:range xsd:duration .
            """));

        assertThat(personProperties(result).get(0).type())
            .isEqualTo(OntologyPropertyType.STRING);
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("unsupported rdfs:range"));
    }

    @Test
    void conflictingRangesFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                rdfs:range xsd:string, xsd:integer .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting rdfs:range");
    }

    @Test
    void conflictingRangesFailClosedEvenWhenValueTypeIsPresent() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:valueType "integer" ;
                rdfs:range xsd:integer, xsd:boolean .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting rdfs:range");
    }

    @Test
    void duplicateRangesWithValueTypeAreDeduplicated() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:valueType "integer" ;
                rdfs:range xsd:integer, xsd:integer .
            """));

        assertThat(personProperties(result).get(0).type())
            .isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void duplicateRangesAreDeduplicated() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                rdfs:range xsd:integer, xsd:integer .
            """));

        assertThat(personProperties(result).get(0).type())
            .isEqualTo(OntologyPropertyType.INTEGER);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void conflictingLabelsOnTheSamePropertyFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "Age", "age" ; lg:owner :Person .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting values");
    }

    @Test
    void conflictingCommentsOnTheSamePropertyFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                rdfs:comment "first", "second" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting values");
    }

    @Test
    void identicalLabelsAndCommentsAreDeduplicated() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p", "p" ; lg:owner :Person ;
                rdfs:comment "same", "same" .
            """));

        assertThat(personProperties(result).get(0).name()).isEqualTo("p");
        assertThat(personProperties(result).get(0).description()).isEqualTo("same");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void conflictingRelationAttributeLabelsFailClosedEvenWhenAttributeNameIsPresent() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :worksFor a owl:ObjectProperty ; rdfs:label "worksFor" ;
                rdfs:domain :Person ; rdfs:range :Org .
            :since a lg:RelationAttribute ; lg:owner :worksFor ;
                lg:attributeName "since" ;
                rdfs:label "x", "y" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting values");
    }

    @Test
    void conflictingRelationAttributeCommentsFailClosedEvenWhenDescriptionIsPresent() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :worksFor a owl:ObjectProperty ; rdfs:label "worksFor" ;
                rdfs:domain :Person ; rdfs:range :Org .
            :since a lg:RelationAttribute ; lg:owner :worksFor ;
                lg:attributeName "since" ;
                lg:description "preferred" ;
                rdfs:comment "first", "second" .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting values");
    }

    @Test
    void conflictingPatternSourceClassesFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    lg:sourceClass :Person, :Org ;
                    lg:targetClass :Person
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("lg:sourceClass")
            .hasMessageContaining("conflicting values");
    }

    @Test
    void conflictingPatternTargetClassesFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    lg:sourceClass :Person ;
                    lg:targetClass :Person, :Org
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("lg:targetClass")
            .hasMessageContaining("conflicting values");
    }

    @Test
    void conflictingPatternFallbackEndpointsFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    rdfs:domain :Person, :Org ;
                    lg:targetClass :Person
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("rdfs:domain")
            .hasMessageContaining("conflicting values");
    }

    @Test
    void extensionPatternEndpointsTakePriorityOverFallbacks() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    lg:sourceClass :Person ;
                    rdfs:domain :Org ;
                    lg:targetClass :Org ;
                    rdfs:range :Person
                ] .
            """));

        OntologyRelationType knows = result.ontology().relationTypes().get(0);
        assertThat(knows.patterns())
            .containsExactly(new OntologyPattern("Person", "Org"));
    }

    @Test
    void anonymousPatternSourceDoesNotFallBackToDomain() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    lg:sourceClass [ ] ;
                    rdfs:domain :Org ;
                    lg:targetClass :Person
                ] .
            """));

        assertThat(result.ontology().relationTypes().get(0).patterns()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("Anonymous or missing endpoint class"));
    }

    @Test
    void literalPatternTargetDoesNotFallBackToRange() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                lg:relationPattern [
                    lg:sourceClass :Person ;
                    lg:targetClass "Org" ;
                    rdfs:range :Org
                ] .
            """));

        assertThat(result.ontology().relationTypes().get(0).patterns()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("Anonymous or missing endpoint class"));
    }

    @Test
    void subjectDomainAndRangeAreReportedWhenExplicitPatternsExist() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :knows a owl:ObjectProperty ; rdfs:label "knows" ;
                rdfs:domain :Person, :Org ;
                lg:relationPattern [
                    lg:sourceClass :Person ;
                    lg:targetClass :Org
                ] .
            """));

        assertThat(result.ontology().relationTypes().get(0).patterns())
            .containsExactly(new OntologyPattern("Person", "Org"));
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("rdfs:domain/rdfs:range; the latter are ignored"));
    }

    @Test
    void conflictingSourceBindingsFailClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:sourceBinding [
                    a lg:SourceBinding ; lg:tableName "a"
                ] , [
                    a lg:SourceBinding ; lg:tableName "b"
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("conflicting lg:SourceBinding");
    }

    @Test
    void identicalSourceBindingsAreDeduplicated() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:sourceBinding [
                    a lg:SourceBinding ; lg:tableName "t" ; lg:column "c"
                ] , [
                    a lg:SourceBinding ; lg:tableName "t" ; lg:column "c"
                ] .
            """));

        assertThat(personProperties(result).get(0).sourceBinding())
            .isEqualTo(new SourceBinding("t", "c", null, null, null));
        assertThat(result.stats().sourceBindings()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void invalidSourceBindingFailsClosed() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:sourceBinding [
                    a lg:SourceBinding ; lg:column "c"
                ] .
            """)))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Invalid lg:SourceBinding");
    }

    @Test
    void nullableBindingIsImported() {
        ImportResult result = OntologyRdfImporter.importOntology(doc("""
            :p a owl:DatatypeProperty ; rdfs:label "p" ; lg:owner :Person ;
                lg:sourceBinding [
                    a lg:SourceBinding ; lg:tableName "t" ; lg:column "c" ;
                        lg:nullable true
                ] .
            """));

        assertThat(personProperties(result).get(0).sourceBinding())
            .isEqualTo(new SourceBinding("t", "c", null, null, Boolean.TRUE));
    }

    private static java.util.List<OntologyProperty> personProperties(ImportResult result) {
        OntologyEntityType person = result.ontology().entityTypes().stream()
            .filter(type -> type.label().equals("Person"))
            .findFirst()
            .orElseThrow();
        return person.properties();
    }
}
