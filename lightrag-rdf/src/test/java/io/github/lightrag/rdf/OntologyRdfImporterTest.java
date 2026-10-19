package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Ontology;
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

class OntologyRdfImporterTest {

    static final String TTL_FIXTURE = """
        @prefix : <https://example.com/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        @prefix lg: <https://example.com/lg#> .

        <https://example.com/onto> a owl:Ontology ;
            owl:versionInfo "2.0" ;
            lg:definitionHash "abc123" .

        :Person a owl:Class ; rdfs:label "Person" ; rdfs:comment "A person" .
        :Organization a owl:Class ; rdfs:label "Organization" .

        :worksFor a owl:ObjectProperty ; rdfs:label "works for" ;
            rdfs:domain :Person ; rdfs:range :Organization .

        :knows a owl:ObjectProperty ; rdfs:label "knows" ;
            lg:relationPattern [ lg:sourceClass :Person ; lg:targetClass :Person ] ,
                                [ lg:sourceClass :Person ; lg:targetClass :Organization ] .

        :name a owl:DatatypeProperty ; rdfs:label "name" ; lg:owner :Person ;
            rdfs:domain :Person ; rdfs:range xsd:string ;
            lg:sourceBinding [ a lg:SourceBinding ; lg:tableName "person" ; lg:column "name" ] .

        :age a owl:DatatypeProperty ; rdfs:label "age" ; rdfs:domain :Person ;
            rdfs:range xsd:integer .

        :skill a owl:DatatypeProperty ; rdfs:label "skill" ; lg:valueType "LIST" ;
            rdfs:domain [ owl:unionOf ( :Person :Organization ) ] .

        :since a lg:RelationAttribute ; lg:owner :worksFor ; lg:attributeName "since" ;
            lg:valueType "date" ; lg:description "Start date" .
        """;

    static final String RDFXML_FIXTURE = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rdf:RDF
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
            xmlns:owl="http://www.w3.org/2002/07/owl#"
            xmlns:xsd="http://www.w3.org/2001/XMLSchema#">
          <owl:Class rdf:about="https://example.com/onto#Company">
            <rdfs:label>Company</rdfs:label>
          </owl:Class>
          <owl:ObjectProperty rdf:about="https://example.com/onto#employs">
            <rdfs:label>employs</rdfs:label>
            <rdfs:domain rdf:resource="https://example.com/onto#Company"/>
            <rdfs:range rdf:resource="https://example.com/onto#Company"/>
          </owl:ObjectProperty>
          <owl:DatatypeProperty rdf:about="https://example.com/onto#founded">
            <rdfs:label>founded</rdfs:label>
            <rdfs:domain rdf:resource="https://example.com/onto#Company"/>
            <rdfs:range rdf:resource="http://www.w3.org/2001/XMLSchema#date"/>
          </owl:DatatypeProperty>
        </rdf:RDF>
        """;

    static final String JSONLD_FIXTURE = """
        {
          "@context": {
            "owl": "http://www.w3.org/2002/07/owl#",
            "rdfs": "http://www.w3.org/2000/01/rdf-schema#",
            "xsd": "http://www.w3.org/2001/XMLSchema#",
            "lg": "https://example.com/lg#",
            "onto": "https://example.com/onto#"
          },
          "@graph": [
            {
              "@id": "onto:Person",
              "@type": "owl:Class",
              "rdfs:label": "Person"
            },
            {
              "@id": "onto:knows",
              "@type": "owl:ObjectProperty",
              "rdfs:label": "knows",
              "rdfs:domain": { "@id": "onto:Person" },
              "rdfs:range": { "@id": "onto:Person" }
            },
            {
              "@id": "onto:name",
              "@type": "owl:DatatypeProperty",
              "rdfs:label": "name",
              "lg:owner": { "@id": "onto:Person" },
              "rdfs:range": { "@id": "xsd:string" }
            }
          ]
        }
        """;

    @Test
    void importsTurtleOntology() {
        ImportResult result = OntologyRdfImporter.importOntology(TTL_FIXTURE);

        Ontology ontology = result.ontology();
        assertThat(ontology.version()).isEqualTo("2.0");
        assertThat(ontology.metadata()).containsEntry("definitionHash", "abc123");
        assertThat(ontology.entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Organization", "Person");

        OntologyEntityType organization = ontology.entityTypes().get(0);
        assertThat(organization.properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("skill", OntologyPropertyType.LIST));

        OntologyEntityType person = ontology.entityTypes().get(1);
        assertThat(person.description()).isEqualTo("A person");
        assertThat(person.properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(
                tuple("age", OntologyPropertyType.INTEGER),
                tuple("name", OntologyPropertyType.STRING),
                tuple("skill", OntologyPropertyType.LIST)
            );
        assertThat(person.properties().get(1).sourceBinding())
            .isEqualTo(new SourceBinding("person", "name", null, null, null));

        assertThat(ontology.relationTypes())
            .extracting(OntologyRelationType::label)
            .containsExactly("knows", "works for");

        OntologyRelationType knows = ontology.relationTypes().get(0);
        assertThat(knows.patterns()).containsExactly(
            new OntologyPattern("Person", "Organization"),
            new OntologyPattern("Person", "Person")
        );

        OntologyRelationType worksFor = ontology.relationTypes().get(1);
        assertThat(worksFor.patterns())
            .containsExactly(new OntologyPattern("Person", "Organization"));
        assertThat(worksFor.properties())
            .extracting(OntologyProperty::name, OntologyProperty::type, OntologyProperty::description)
            .containsExactly(tuple("since", OntologyPropertyType.DATE, "Start date"));

        assertThat(result.warnings()).isEmpty();
        assertThat(result.stats()).isEqualTo(
            new ImportResult.ImportStats(47, 2, 2, 5, 3, 1, 0)
        );
    }

    @Test
    void importsRdfXmlOntology() {
        ImportResult result = OntologyRdfImporter.importOntology(RDFXML_FIXTURE);

        Ontology ontology = result.ontology();
        assertThat(ontology.entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Company");
        assertThat(ontology.entityTypes().get(0).properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("founded", OntologyPropertyType.DATE));
        assertThat(ontology.relationTypes())
            .extracting(OntologyRelationType::label)
            .containsExactly("employs");
        assertThat(ontology.relationTypes().get(0).patterns())
            .containsExactly(new OntologyPattern("Company", "Company"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void importsJsonLdWithInlineContext() {
        ImportResult result = OntologyRdfImporter.importOntology(JSONLD_FIXTURE);

        Ontology ontology = result.ontology();
        assertThat(ontology.entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Person");
        assertThat(ontology.entityTypes().get(0).properties())
            .extracting(OntologyProperty::name, OntologyProperty::type)
            .containsExactly(tuple("name", OntologyPropertyType.STRING));
        assertThat(ontology.relationTypes())
            .extracting(OntologyRelationType::label)
            .containsExactly("knows");
        assertThat(ontology.relationTypes().get(0).patterns())
            .containsExactly(new OntologyPattern("Person", "Person"));
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void autoDetectsAllSupportedFormats() {
        assertThat(OntologyRdfImporter.importOntology(TTL_FIXTURE)
            .ontology()
            .entityTypes())
            .hasSize(2);
        assertThat(OntologyRdfImporter.importOntology(RDFXML_FIXTURE)
            .ontology()
            .entityTypes())
            .hasSize(1);
        assertThat(OntologyRdfImporter.importOntology(JSONLD_FIXTURE)
            .ontology()
            .entityTypes())
            .hasSize(1);
    }

    @Test
    void stripsUtf8ByteOrderMark() {
        ImportResult result = OntologyRdfImporter.importOntology("\uFEFF" + JSONLD_FIXTURE);

        assertThat(result.ontology().entityTypes()).hasSize(1);
    }

    @Test
    void explicitFormatIsHonoured() {
        RdfImportOptions turtle = RdfImportOptions.builder().format(RdfImportFormat.TURTLE).build();
        assertThat(OntologyRdfImporter.importOntology(TTL_FIXTURE, turtle)
            .ontology()
            .entityTypes())
            .hasSize(2);

        RdfImportOptions jsonLd = RdfImportOptions.builder().format(RdfImportFormat.JSONLD).build();
        assertThat(OntologyRdfImporter.importOntology(JSONLD_FIXTURE, jsonLd)
            .ontology()
            .entityTypes())
            .hasSize(1);
    }

    @Test
    void sourceBindingsCanBeDisabled() {
        RdfImportOptions options = RdfImportOptions.builder()
            .includeSourceBindings(false)
            .build();
        ImportResult result = OntologyRdfImporter.importOntology(TTL_FIXTURE, options);

        OntologyProperty name = result.ontology().entityTypes().get(1).properties().get(1);
        assertThat(name.name()).isEqualTo("name");
        assertThat(name.sourceBinding()).isNull();
        assertThat(result.stats().sourceBindings()).isZero();
    }

    @Test
    void percentDecodesIriLocalNames() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix lg: <https://example.com/lg#> .

            <https://example.com/onto#Person> a owl:Class ; rdfs:label "Person" .
            <https://example.com/onto#M%20y%20Class> a owl:Class .
            <https://example.com/onto#%E4%B8%AD%E6%96%87> a owl:Class .
            <https://example.com/onto#a%20b> a owl:DatatypeProperty ;
                lg:owner <https://example.com/onto#Person> .
            """;

        ImportResult result = OntologyRdfImporter.importOntology(ttl);

        assertThat(result.ontology().entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("M y Class", "Person", "中文");
        assertThat(result.ontology().entityTypes().get(1).properties())
            .extracting(OntologyProperty::name)
            .containsExactly("a b");
    }

    @Test
    void standardVocabularyIsNotImported() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <http://www.w3.org/2002/07/owl#Thing> a owl:Class, rdfs:Class .
            <https://example.com/onto#Person> a owl:Class ; rdfs:label "Person" .
            <https://example.com/onto#p> a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain <http://www.w3.org/2002/07/owl#Thing> .
            """;

        ImportResult result = OntologyRdfImporter.importOntology(ttl);

        assertThat(result.ontology().entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Person");
        assertThat(result.ontology().entityTypes().get(0).properties()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("is not a declared owl:Class"));
    }

    @Test
    void entityAndRelationMayShareALabel() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <https://example.com/onto#PersonEntity> a owl:Class ; rdfs:label "Person" .
            <https://example.com/onto#PersonRelation> a owl:ObjectProperty ; rdfs:label "Person" .
            """;

        ImportResult result = OntologyRdfImporter.importOntology(ttl);

        assertThat(result.ontology().entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Person");
        assertThat(result.ontology().relationTypes())
            .extracting(OntologyRelationType::label)
            .containsExactly("Person");
    }

    @Test
    void objectPropertyWithoutDomainOrRangeHasNoPatterns() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <https://example.com/onto#relatedTo> a owl:ObjectProperty ;
                rdfs:label "related to" .
            """;

        ImportResult result = OntologyRdfImporter.importOntology(ttl);

        assertThat(result.ontology().relationTypes()).hasSize(1);
        assertThat(result.ontology().relationTypes().get(0).patterns()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void duplicateEntityIdentityFailsClosed() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <https://example.com/onto#A> a owl:Class ; rdfs:label "Person" .
            <https://example.com/onto#B> a owl:Class ; rdfs:label "person" .
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(ttl))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Duplicate entity identity");
    }

    @Test
    void duplicateRelationIdentityFailsClosed() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

            <https://example.com/onto#a> a owl:ObjectProperty ; rdfs:label "knows" .
            <https://example.com/onto#b> a owl:ObjectProperty ; rdfs:label "KNOWS" .
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(ttl))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Duplicate relation identity");
    }

    @Test
    void emptyDocumentProducesAnEmptyOntologyWithAWarning() {
        ImportResult result = OntologyRdfImporter.importOntology("");

        assertThat(result.ontology().entityTypes()).isEmpty();
        assertThat(result.ontology().relationTypes()).isEmpty();
        assertThat(result.warnings())
            .contains("The RDF document contained no statements.");
    }

    @Test
    void unknownAxiomsAreWarnedAboutOrSilentlyIgnored() {
        String ttl = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .

            <https://example.com/onto#Person> a owl:Class ; rdfs:label "Person" .
            <https://example.com/onto#Person> rdfs:subClassOf <https://example.com/onto#Thing> .
            <https://example.com/ontology> a owl:Ontology ;
                owl:imports <https://example.com/imported> .
            <https://example.com/shape> a sh:NodeShape .
            """;

        ImportResult warned = OntologyRdfImporter.importOntology(
            ttl,
            RdfImportOptions.builder().allowUnknownAxioms(true).build()
        );
        assertThat(warned.warnings()).anyMatch(w -> w.contains("#subClassOf"));
        assertThat(warned.warnings()).anyMatch(w -> w.contains("owl:imports is never followed"));
        assertThat(warned.warnings()).anyMatch(w -> w.contains("shacl#NodeShape"));
        assertThat(warned.stats().ignoredAxiomStatements()).isEqualTo(3);
        assertThat(warned.warnings()).isSorted();

        ImportResult silent = OntologyRdfImporter.importOntology(
            ttl,
            RdfImportOptions.builder().allowUnknownAxioms(false).build()
        );
        assertThat(silent.warnings()).isEmpty();
    }

    @Test
    void resultIsDeterministic() {
        ImportResult first = OntologyRdfImporter.importOntology(TTL_FIXTURE);
        ImportResult second = OntologyRdfImporter.importOntology(TTL_FIXTURE);

        assertThat(second.ontology()).isEqualTo(first.ontology());
        assertThat(second.warnings()).isEqualTo(first.warnings());
        assertThat(second.stats()).isEqualTo(first.stats());
    }
}
