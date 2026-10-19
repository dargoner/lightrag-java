package io.github.lightrag.ontology.rdf;

import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyRdfExporterTest {

    private static final String ONTOLOGY_IRI = "https://example.com/onto";
    private static final String BASE = "https://example.com/onto/";
    private static final RdfExportOptions OPTIONS = RdfExportOptions.builder(ONTOLOGY_IRI).build();

    private final OntologyRdfExporter exporter = new OntologyRdfExporter();

    @Test
    void exportsGoldenOntologyTriples() {
        String turtle = exporter.export(sampleOntology(false), OPTIONS);

        assertThat(turtle)
            .contains("@prefix lg: <https://example.com/onto/> .")
            .contains("@prefix owl: <http://www.w3.org/2002/07/owl#> .")
            .contains("@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .")
            .contains("@prefix sh: <http://www.w3.org/ns/shacl#> .")
            .contains("<https://example.com/onto> a owl:Ontology .")
            .contains("<https://example.com/onto> owl:versionInfo \"v1\" .")
            .contains("<https://example.com/onto> lg:version \"v1\" .")
            .contains("<https://example.com/onto> lg:definitionHash \"hash-1\" .")
            .contains("<" + BASE + "class/person> a owl:Class .")
            .contains("<" + BASE + "class/person> rdfs:label \"Person\" .")
            .contains("<" + BASE + "class/person> rdfs:comment \"A person\" .")
            .contains("<" + BASE + "class/organization> a owl:Class .")
            .contains("<" + BASE + "relation/works%20at> a owl:ObjectProperty .")
            .contains("<" + BASE + "relation/works%20at> rdfs:label \"works at\" .")
            .contains("<" + BASE + "relation/works%20at> rdfs:domain <" + BASE + "class/person> .")
            .contains("<" + BASE + "relation/works%20at> rdfs:range <" + BASE + "class/organization> .")
            .contains("<" + BASE + "relation/knows> a owl:ObjectProperty .")
            .contains("<" + BASE + "attribute/entity/person/name> a owl:DatatypeProperty .")
            .contains("<" + BASE + "attribute/entity/person/name> rdfs:label \"name\" .")
            .contains("<" + BASE + "attribute/entity/person/name> rdfs:comment \"full name\" .")
            .contains("<" + BASE + "attribute/entity/person/name> lg:owner <" + BASE + "class/person> .")
            .contains("<" + BASE + "attribute/entity/person/name> rdfs:domain <" + BASE + "class/person> .")
            .contains("<" + BASE + "attribute/entity/person/name> lg:valueType \"STRING\" .")
            .contains("<" + BASE + "attribute/entity/person/name> rdfs:range xsd:string .")
            .contains("<" + BASE + "attribute/entity/person/age> rdfs:range xsd:integer .")
            .contains("<" + BASE + "attribute/entity/person/tags> lg:valueType \"LIST\" .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> a lg:RelationAttribute .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> lg:owner <"
                + BASE + "relation/works%20at> .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> lg:attributeName \"since\" .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> lg:valueType \"DATE\" .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> lg:description \"start date\" .");
    }

    @Test
    void entityPropertiesAreOwnerScopedAndNeverShareDomains() {
        String turtle = exporter.export(sampleOntology(false), OPTIONS);

        assertThat(turtle)
            .contains("<" + BASE + "attribute/entity/person/name> lg:owner <" + BASE + "class/person> .")
            .contains("<" + BASE + "attribute/entity/person/name> rdfs:domain <" + BASE + "class/person> .")
            .contains("<" + BASE + "attribute/entity/organization/name> lg:owner <"
                + BASE + "class/organization> .")
            .contains("<" + BASE + "attribute/entity/organization/name> rdfs:domain <"
                + BASE + "class/organization> .")
            .doesNotContain("owl:unionOf")
            .doesNotContain("attribute/entity/name>");
    }

    @Test
    void multiplePatternsBecomeConcreteRelationPatternNodes() {
        String turtle = exporter.export(sampleOntology(false), OPTIONS);
        String patternNode = BASE + "relationPattern/knows/person/person";

        assertThat(turtle)
            .doesNotContain("<" + BASE + "relation/knows> rdfs:domain")
            .doesNotContain("<" + BASE + "relation/knows> rdfs:range")
            .contains("<" + BASE + "relation/knows> lg:relationPattern <" + patternNode + "> .")
            .contains("<" + patternNode + "> a lg:RelationPattern .")
            .contains("<" + patternNode + "> lg:relation <" + BASE + "relation/knows> .")
            .contains("<" + patternNode + "> lg:sourceClass <" + BASE + "class/person> .")
            .contains("<" + patternNode + "> lg:targetClass <" + BASE + "class/person> .");

        assertThat(turtle).contains(
            "<" + BASE + "relation/knows> lg:relationPattern <"
                + BASE + "relationPattern/knows/person/organization> ."
        );
    }

    @Test
    void zeroPatternsProduceNoDomainRangeOrPatternNodes() {
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType("Person", null, List.of(), null)),
            List.of(new OntologyRelationType("mentions", null, List.of(), List.of(), null)),
            null,
            Map.of()
        );

        String turtle = exporter.export(ontology, OPTIONS);

        assertThat(turtle)
            .doesNotContain("<" + BASE + "relation/mentions> rdfs:domain")
            .doesNotContain("<" + BASE + "relation/mentions> rdfs:range")
            .doesNotContain("relationPattern/mentions");
    }

    @Test
    void entityAndRelationWithTheSameNameGetDistinctIris() {
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType("works at", null, List.of(), null)),
            List.of(new OntologyRelationType("works at", null, List.of(), List.of(), null)),
            null,
            Map.of()
        );

        String turtle = exporter.export(ontology, OPTIONS);

        assertThat(turtle)
            .contains("<" + BASE + "class/works%20at> a owl:Class .")
            .contains("<" + BASE + "relation/works%20at> a owl:ObjectProperty .");
    }

    @Test
    void identicalDuplicatePropertiesAreDeduplicated() {
        OntologyProperty age = new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null);
        Ontology ontology = ontologyWithEntityProperties(age, age);

        String turtle = exporter.export(ontology, OPTIONS);
        long declarations = turtle.lines()
            .filter(line -> line.contains("<" + BASE + "attribute/entity/person/age> a owl:DatatypeProperty ."))
            .count();

        assertThat(declarations).isEqualTo(1L);
    }

    @Test
    void conflictingEntityPropertiesFailClosed() {
        OntologyProperty string = new OntologyProperty("Age", OntologyPropertyType.STRING, null, null);
        OntologyProperty integer = new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null);

        assertThatThrownBy(() -> exporter.export(ontologyWithEntityProperties(string, integer), OPTIONS))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("entity attribute")
            .hasMessageContaining("display name")
            .hasMessageContaining("valueType");
    }

    @Test
    void conflictingDescriptionsAndSourceBindingsFailClosed() {
        OntologyProperty first = new OntologyProperty("Age", OntologyPropertyType.INTEGER, "one", null);
        OntologyProperty second = new OntologyProperty("age", OntologyPropertyType.INTEGER, "two", null);

        assertThatThrownBy(() -> exporter.export(ontologyWithEntityProperties(first, second), OPTIONS))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("description");

        SourceBinding binding = new SourceBinding("people", "age", null, null, null);
        OntologyProperty bound = new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, binding);
        OntologyProperty unbound = new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null);

        assertThatThrownBy(() -> exporter.export(ontologyWithEntityProperties(bound, unbound), OPTIONS))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("sourceBinding");
    }

    @Test
    void conflictingRelationPropertiesFailClosed() {
        OntologyRelationType relation = new OntologyRelationType(
            "works at",
            null,
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(
                new OntologyProperty("Since", OntologyPropertyType.DATE, null, null),
                new OntologyProperty("since", OntologyPropertyType.STRING, null, null)
            ),
            null
        );
        Ontology ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", null, List.of(), null),
                new OntologyEntityType("Organization", null, List.of(), null)
            ),
            List.of(relation),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> exporter.export(ontology, OPTIONS))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("relation attribute");
    }

    @Test
    void entityAndRelationPropertiesWithTheSameNameAreNotConflicts() {
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                null,
                List.of(new OntologyProperty("since", OntologyPropertyType.DATE, null, null)),
                null
            )),
            List.of(new OntologyRelationType(
                "works at",
                null,
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(new OntologyProperty("since", OntologyPropertyType.DATE, null, null)),
                null
            )),
            null,
            Map.of()
        );

        String turtle = exporter.export(ontology, OPTIONS);

        assertThat(turtle)
            .contains("<" + BASE + "attribute/entity/person/since> a owl:DatatypeProperty .")
            .contains("<" + BASE + "attribute/relation/works%20at/since> a lg:RelationAttribute .");
    }

    @Test
    void exportIsDeterministicAcrossRepeatedAndReorderedInput() {
        Ontology sample = sampleOntology(false);
        String first = exporter.export(sample, OPTIONS);
        String second = exporter.export(sample, OPTIONS);
        String reordered = exporter.export(sampleOntology(true), OPTIONS);

        assertThat(second).isEqualTo(first);
        assertThat(reordered).isEqualTo(first);
    }

    @Test
    void literalsAreEscapedAndMeasuredBeforeEscaping() {
        String label = "Per\"son\\ mid\nline\tend";
        String description = "desc \"quoted\" \\ slash\nnext";
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType(label, description, List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        String turtle = exporter.export(ontology, OPTIONS);
        String escapedLabel = TurtleWriter.escapeLiteral(label.strip());

        assertThat(escapedLabel)
            .contains("\\\"")
            .contains("\\\\")
            .contains("\\n")
            .contains("\\t");
        assertThat(turtle)
            .contains("rdfs:label \"" + escapedLabel + "\" .")
            .doesNotContain("line\nnext");
        assertThat(TurtleWriter.escapeLiteral("a\u0001b")).isEqualTo("a\\u0001b");
    }

    @Test
    void versionAndDefinitionHashCanBeOmitted() {
        RdfExportOptions noVersion = RdfExportOptions.builder(ONTOLOGY_IRI)
            .includeVersion(false)
            .build();

        String turtle = exporter.export(sampleOntology(false), noVersion);

        assertThat(turtle)
            .doesNotContain("owl:versionInfo")
            .doesNotContain("definitionHash");
    }

    @Test
    void scalarTypesMapToXsdRangesAndShaclDatatypes() {
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType(
                "Thing",
                null,
                List.of(
                    new OntologyProperty("s", OntologyPropertyType.STRING, null, null),
                    new OntologyProperty("i", OntologyPropertyType.INTEGER, null, null),
                    new OntologyProperty("f", OntologyPropertyType.FLOAT, null, null),
                    new OntologyProperty("b", OntologyPropertyType.BOOLEAN, null, null),
                    new OntologyProperty("d", OntologyPropertyType.DATE, null, null),
                    new OntologyProperty("l", OntologyPropertyType.LIST, null, null)
                ),
                null
            )),
            List.of(),
            null,
            Map.of()
        );

        String turtle = exporter.export(ontology, OPTIONS);

        assertThat(turtle)
            .contains("<" + BASE + "attribute/entity/thing/s> rdfs:range xsd:string .")
            .contains("<" + BASE + "attribute/entity/thing/i> rdfs:range xsd:integer .")
            .contains("<" + BASE + "attribute/entity/thing/f> rdfs:range xsd:double .")
            .contains("<" + BASE + "attribute/entity/thing/b> rdfs:range xsd:boolean .")
            .contains("<" + BASE + "attribute/entity/thing/d> rdfs:range xsd:date .")
            .doesNotContain("<" + BASE + "attribute/entity/thing/l> rdfs:range")
            .contains("<" + BASE + "attribute/entity/thing/l> lg:valueType \"LIST\" .")
            .contains("<" + BASE + "shapes/class/thing/property/f> sh:datatype xsd:double .")
            .contains("<" + BASE + "shapes/class/thing/property/b> sh:datatype xsd:boolean .")
            .contains("<" + BASE + "shapes/class/thing/property/d> sh:datatype xsd:date .");
    }

    @Test
    void shaclCoversOnlyEntityPropertiesAndKeepsListCardinality() {
        String turtle = exporter.export(sampleOntology(false), OPTIONS);
        String listShape = BASE + "shapes/class/person/property/tags";

        assertThat(turtle)
            .contains("<" + BASE + "shapes/class/person/shape> a sh:NodeShape .")
            .contains("<" + BASE + "shapes/class/person/shape> sh:targetClass <"
                + BASE + "class/person> .")
            .contains("<" + BASE + "shapes/class/person/shape> sh:property <" + listShape + "> .")
            .contains("<" + listShape + "> a sh:PropertyShape .")
            .contains("<" + listShape + "> sh:path <" + BASE + "attribute/entity/person/tags> .")
            .doesNotContain("<" + listShape + "> sh:datatype")
            .contains("<" + listShape + "> sh:maxCount \"1\"^^xsd:integer .")
            .contains("# LIST property " + BASE + "attribute/entity/person/tags")
            .doesNotContain("<" + BASE + "shapes/attribute/relation")
            .doesNotContain("sh:path <" + BASE + "attribute/relation/works%20at/since>");
    }

    @Test
    void shaclCanBeOmittedFromExportAndRequestedSeparately() {
        RdfExportOptions noShacl = RdfExportOptions.builder(ONTOLOGY_IRI)
            .includeShacl(false)
            .build();

        String withoutShacl = exporter.export(sampleOntology(false), noShacl);
        String shaclOnly = exporter.exportShacl(sampleOntology(false), noShacl);

        assertThat(withoutShacl)
            .doesNotContain("sh:NodeShape")
            .doesNotContain("sh:PropertyShape");
        assertThat(shaclOnly)
            .contains("sh:NodeShape")
            .contains("sh:PropertyShape")
            .doesNotContain("a owl:Ontology")
            .doesNotContain("owl:DatatypeProperty");
    }

    @Test
    void sourceBindingsAreAttachedToOwnersAndCanBeDisabled() {
        String turtle = exporter.export(sampleOntology(false), OPTIONS);
        String binding = BASE + "attribute/entity/person/name/sourceBinding";

        assertThat(turtle)
            .contains("<" + BASE + "attribute/entity/person/name> lg:sourceBinding <" + binding + "> .")
            .contains("<" + binding + "> a lg:SourceBinding .")
            .contains("<" + binding + "> lg:tableName \"people\" .")
            .contains("<" + binding + "> lg:column \"name\" .")
            .contains("<" + binding + "> lg:nullable \"false\"^^xsd:boolean .")
            .contains("<" + BASE + "class/person> lg:sourceBinding <"
                + BASE + "class/person/sourceBinding> .");

        RdfExportOptions noBindings = RdfExportOptions.builder(ONTOLOGY_IRI)
            .includeSourceBindings(false)
            .build();
        String withoutBindings = exporter.export(sampleOntology(false), noBindings);

        assertThat(withoutBindings)
            .doesNotContain("SourceBinding")
            .doesNotContain("sourceBinding");
    }

    @Test
    void ontologyIriMustNotCollideWithGeneratedVocabularyIris() {
        RdfExportOptions conflicting = RdfExportOptions.builder(BASE + "class/person")
            .vocabularyBase(BASE)
            .build();
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType("Person", null, List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> exporter.export(ontology, conflicting))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("collides");
    }

    @Test
    void unsupportedLiteralOrIriSizesFailClosed() {
        Ontology hugeLiteral = new Ontology(
            List.of(new OntologyEntityType("Person", "x".repeat(64 * 1024 + 1), List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        assertThatThrownBy(() -> exporter.export(hugeLiteral, OPTIONS))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("65536");
    }

    @Test
    void optionsValidateDefaultsAndAbsoluteSlashTerminatedBases() {
        RdfExportOptions defaults = RdfExportOptions.defaults("https://example.com/onto");

        assertThat(defaults.ontologyIri()).isEqualTo("https://example.com/onto");
        assertThat(defaults.vocabularyBase()).isEqualTo("https://example.com/onto/");
        assertThat(defaults.shapesBase()).isEqualTo("https://example.com/onto/shapes/");
        assertThat(defaults.prefix()).isEqualTo("lg");
        assertThat(defaults.includeShacl()).isTrue();
        assertThat(defaults.includeVersion()).isTrue();

        assertThat(RdfExportOptions.defaults("https://example.com/onto/").vocabularyBase())
            .isEqualTo("https://example.com/onto/");
        assertThat(RdfExportOptions.defaults("https://example.com/onto#").vocabularyBase())
            .isEqualTo("https://example.com/onto/");

        assertThatThrownBy(() -> RdfExportOptions.builder("relative/path").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute IRI");
        assertThatThrownBy(() -> RdfExportOptions.builder("https://example.com/onto")
            .vocabularyBase("https://example.com/vocabulary")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end with '/'");
        assertThatThrownBy(() -> RdfExportOptions.builder("https://example.com/onto")
            .shapesBase("https://example.com/shapes")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end with '/'");
        assertThatThrownBy(() -> RdfExportOptions.builder("https://example.com/onto")
            .prefix("rdf")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("built-in");
        assertThatThrownBy(() -> RdfExportOptions.builder("https://example.com/onto")
            .prefix("1bad")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prefix");
    }

    private static Ontology ontologyWithEntityProperties(OntologyProperty... properties) {
        return new Ontology(
            List.of(new OntologyEntityType("Person", null, List.of(properties), null)),
            List.of(),
            null,
            Map.of()
        );
    }

    private static Ontology sampleOntology(boolean reverse) {
        OntologyProperty name = new OntologyProperty(
            "name",
            OntologyPropertyType.STRING,
            "full name",
            new SourceBinding("people", "name", null, null, false)
        );
        OntologyProperty age = new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null);
        OntologyProperty tags = new OntologyProperty("tags", OntologyPropertyType.LIST, null, null);

        List<OntologyProperty> personProperties = reverse
            ? List.of(tags, age, name)
            : List.of(name, age, tags);

        OntologyEntityType person = new OntologyEntityType(
            "Person",
            "A person",
            personProperties,
            new SourceBinding("people", null, null, null, null)
        );
        OntologyEntityType organization = new OntologyEntityType(
            "Organization",
            "An organization",
            List.of(new OntologyProperty("name", OntologyPropertyType.STRING, "org name", null)),
            null
        );

        OntologyPattern personToOrganization = new OntologyPattern("Person", "Organization");
        OntologyPattern personToPerson = new OntologyPattern("Person", "Person");
        OntologyRelationType worksAt = new OntologyRelationType(
            "works at",
            "employment",
            List.of(personToOrganization),
            List.of(new OntologyProperty("since", OntologyPropertyType.DATE, "start date", null)),
            null
        );
        OntologyRelationType knows = new OntologyRelationType(
            "knows",
            "social",
            reverse ? List.of(personToOrganization, personToPerson) : List.of(personToPerson, personToOrganization),
            List.of(),
            null
        );

        List<OntologyEntityType> entities = reverse
            ? List.of(organization, person)
            : List.of(person, organization);
        List<OntologyRelationType> relations = reverse
            ? List.of(knows, worksAt)
            : List.of(worksAt, knows);

        return new Ontology(entities, relations, "v1", Map.of("definitionHash", "hash-1"));
    }
}
