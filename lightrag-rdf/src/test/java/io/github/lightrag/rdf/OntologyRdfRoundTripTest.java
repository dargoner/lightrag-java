package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;
import io.github.lightrag.ontology.rdf.OntologyRdfExporter;
import io.github.lightrag.ontology.rdf.RdfExportOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyRdfRoundTripTest {

    private static final String ONTOLOGY_IRI = "https://example.com/roundtrip";
    private static final RdfExportOptions EXPORT_OPTIONS =
        RdfExportOptions.builder(ONTOLOGY_IRI).build();

    @Test
    void exportThenImportPreservesTheSupportedSubset() {
        Ontology original = sampleOntology();
        String turtle = new OntologyRdfExporter().export(original, EXPORT_OPTIONS);

        ImportResult result = OntologyRdfImporter.importOntology(turtle);
        Ontology expected = original.toCanonical();
        Ontology imported = result.ontology().toCanonical();

        assertThat(imported.version()).isEqualTo(expected.version());
        assertThat(imported.metadata()).isEqualTo(expected.metadata());

        Map<String, OntologyEntityType> importedEntities = imported.entityTypes().stream()
            .collect(Collectors.toMap(OntologyEntityType::identityId, Function.identity()));
        assertThat(importedEntities.keySet())
            .isEqualTo(expected.entityTypes().stream()
                .map(OntologyEntityType::identityId).collect(Collectors.toSet()));
        for (OntologyEntityType expectedType : expected.entityTypes()) {
            OntologyEntityType importedType = importedEntities.get(expectedType.identityId());
            assertThat(importedType.description()).isEqualTo(expectedType.description());
            assertThat(importedType.sourceBinding()).isEqualTo(expectedType.sourceBinding());
            assertThat(importedType.properties())
                .containsExactlyInAnyOrderElementsOf(expectedType.properties());
        }

        Map<String, OntologyRelationType> importedRelations = imported.relationTypes().stream()
            .collect(Collectors.toMap(OntologyRelationType::identityId, Function.identity()));
        assertThat(importedRelations.keySet())
            .isEqualTo(expected.relationTypes().stream()
                .map(OntologyRelationType::identityId).collect(Collectors.toSet()));
        for (OntologyRelationType expectedType : expected.relationTypes()) {
            OntologyRelationType importedType = importedRelations.get(expectedType.identityId());
            assertThat(importedType.description()).isEqualTo(expectedType.description());
            assertThat(importedType.sourceBinding()).isEqualTo(expectedType.sourceBinding());
            assertThat(importedType.properties())
                .containsExactlyInAnyOrderElementsOf(expectedType.properties());
            assertThat(importedType.patterns().stream().map(OntologyPattern::identityId).collect(Collectors.toSet()))
                .isEqualTo(expectedType.patterns().stream().map(OntologyPattern::identityId).collect(Collectors.toSet()));
        }
    }

    @Test
    void escapedLabelsAndDescriptionsRoundTrip() {
        String label = "Per\"son\\\n\t\u0001";
        String description = "line\\one\nline\ttwo\u007f";
        Ontology original = new Ontology(
            List.of(new OntologyEntityType(label, description, List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        String turtle = new OntologyRdfExporter().export(original, EXPORT_OPTIONS);
        Ontology imported = OntologyRdfImporter.importOntology(turtle).ontology();

        assertThat(turtle).doesNotContain("\nline\ttwo");
        assertThat(imported.entityTypes()).hasSize(1);
        assertThat(imported.entityTypes().get(0).label()).isEqualTo(label.strip());
        assertThat(imported.entityTypes().get(0).description()).isEqualTo(description.strip());
    }

    private static Ontology sampleOntology() {
        OntologyProperty name = new OntologyProperty(
            "name",
            OntologyPropertyType.STRING,
            "full name",
            new SourceBinding("people", "name", null, null, false)
        );
        OntologyProperty age = new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null);
        OntologyProperty tags = new OntologyProperty("tags", OntologyPropertyType.LIST, null, null);

        OntologyEntityType person = new OntologyEntityType(
            "Person",
            "A person",
            List.of(name, age, tags),
            new SourceBinding("people", null, null, null, null)
        );
        OntologyEntityType organization = new OntologyEntityType(
            "Organization",
            "An organization",
            List.of(new OntologyProperty("name", OntologyPropertyType.STRING, "org name", null)),
            null
        );

        OntologyRelationType worksAt = new OntologyRelationType(
            "works at",
            "employment",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(new OntologyProperty("since", OntologyPropertyType.DATE, "start date", null)),
            new SourceBinding("employments", null, null, null, null)
        );
        OntologyRelationType knows = new OntologyRelationType(
            "knows",
            "social",
            List.of(
                new OntologyPattern("Person", "Person"),
                new OntologyPattern("Person", "Organization")
            ),
            List.of(),
            null
        );

        return new Ontology(
            List.of(person, organization),
            List.of(worksAt, knows),
            "v1",
            Map.of("definitionHash", "hash-1")
        );
    }
}
