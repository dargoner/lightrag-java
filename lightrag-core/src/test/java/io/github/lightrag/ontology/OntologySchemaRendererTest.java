package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OntologySchemaRendererTest {

    @Test
    void rendersLogicalSchemaSegmentInDeclaredOrder() {
        Ontology ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(
                new OntologyRelationType(
                    "works at",
                    "employment",
                    List.of(new OntologyPattern("Person", "Organization")),
                    List.of(),
                    null
                )
            ),
            null,
            Map.of()
        );

        String rendered = OntologySchemaRenderer.render(ontology, null);

        assertThat(rendered).isEqualTo(
            "Business entity types: Person, Organization\n"
                + "Business relationship keywords: works at\n"
                + "Business relationship patterns:\n"
                + "- Person -[works at]-> Organization\n"
                + "Note: the physical graph merges relationships by endpoint pair, so pattern"
                + " direction is a reference only."
        );
    }

    @Test
    void returnsEmptyStringWhenNothingDeclared() {
        Ontology ontology = new Ontology(List.of(), List.of(), null, Map.of());

        assertThat(OntologySchemaRenderer.render(ontology, OntologySchemaRenderer.RenderOptions.defaults()))
            .isEmpty();
    }

    @Test
    void omitsSectionsWithoutDeclarations() {
        Ontology ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );

        assertThat(OntologySchemaRenderer.render(ontology, null))
            .isEqualTo("Business entity types: Person");
    }

    @Test
    void rendersOnePatternLinePerDeclaredPattern() {
        Ontology ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(
                new OntologyRelationType(
                    "works at",
                    "employment",
                    List.of(
                        new OntologyPattern("Person", "Organization"),
                        new OntologyPattern("Person", "Person")
                    ),
                    List.of(),
                    null
                )
            ),
            null,
            Map.of()
        );

        String rendered = OntologySchemaRenderer.render(ontology, null);

        assertThat(rendered)
            .contains("- Person -[works at]-> Organization\n")
            .contains("- Person -[works at]-> Person\n")
            .contains("pattern direction is a reference only");
    }

    @Test
    void omitsDeclaredPropertiesByDefault() {
        Ontology ontology = ontologyWithDeclaredProperties();

        String rendered = OntologySchemaRenderer.render(ontology, null);

        assertThat(rendered).doesNotContain("properties:");
    }

    @Test
    void rendersDeclaredPropertiesWhenOptioned() {
        Ontology ontology = ontologyWithDeclaredProperties();

        String rendered = OntologySchemaRenderer.render(
            ontology, new OntologySchemaRenderer.RenderOptions(true));

        assertThat(rendered)
            .contains("Person properties: name (string)")
            .contains("works at properties: since (date)");
    }

    private static Ontology ontologyWithDeclaredProperties() {
        return new Ontology(
            List.of(
                new OntologyEntityType(
                    "Person",
                    "A person",
                    List.of(new OntologyProperty("name", OntologyPropertyType.STRING, null, null)),
                    null
                )
            ),
            List.of(
                new OntologyRelationType(
                    "works at",
                    "employment",
                    List.of(new OntologyPattern("Person", "Person")),
                    List.of(new OntologyProperty("since", OntologyPropertyType.DATE, null, null)),
                    null
                )
            ),
            null,
            Map.of()
        );
    }
}
