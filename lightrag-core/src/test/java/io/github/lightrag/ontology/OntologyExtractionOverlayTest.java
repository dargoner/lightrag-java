package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyExtractionOverlayTest {

    @Test
    void emptyOverlayHasNoPatternsOrAttributes() {
        var overlay = OntologyExtractionOverlay.empty();

        assertThat(overlay.patterns()).isEmpty();
        assertThat(overlay.attributeBlocks()).isEmpty();
    }

    @Test
    void fromOntologyBuildsPatternMapByRelationLabel() {
        var person = new OntologyEntityType("Person", "A person", List.of(), null);
        var org = new OntologyEntityType("Organization", "An organization", List.of(), null);
        var worksAt = new OntologyRelationType(
            "works at",
            "Employment relation",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(),
            null
        );
        var ontology = new Ontology(List.of(person, org), List.of(worksAt), null, Map.of());

        var overlay = OntologyExtractionOverlay.from(ontology);

        assertThat(overlay.patterns()).containsOnlyKeys("works at");
        assertThat(overlay.patterns().get("works at"))
            .containsExactly(new OntologyPattern("Person", "Organization"));
    }

    @Test
    void fromOntologyBuildsAttributeBlocksForEntityAndRelationProperties() {
        var person = new OntologyEntityType(
            "Person",
            "A person",
            List.of(new OntologyProperty("age", OntologyPropertyType.INTEGER, "in years", null)),
            null
        );
        var worksAt = new OntologyRelationType(
            "works at",
            "Employment relation",
            List.of(),
            List.of(new OntologyProperty("since", OntologyPropertyType.DATE, "start date", null)),
            null
        );
        var ontology = new Ontology(List.of(person), List.of(worksAt), null, Map.of());

        var overlay = OntologyExtractionOverlay.from(ontology);

        assertThat(overlay.attributeBlocks()).containsOnlyKeys("Person", "works at");
        assertThat(overlay.attributeBlocks().get("Person"))
            .contains("Person attributes:")
            .contains("  - age (integer): in years");
        assertThat(overlay.attributeBlocks().get("works at"))
            .contains("works at attributes:")
            .contains("  - since (date): start date");
    }

    @Test
    void fromOntologyIgnoresTypesWithoutPatternsOrProperties() {
        var person = new OntologyEntityType("Person", "A person", List.of(), null);
        var rel = new OntologyRelationType("related to", "Vague relation", List.of(), List.of(), null);
        var ontology = new Ontology(List.of(person), List.of(rel), null, Map.of());

        var overlay = OntologyExtractionOverlay.from(ontology);

        assertThat(overlay.patterns()).isEmpty();
        assertThat(overlay.attributeBlocks()).isEmpty();
    }

    @Test
    void patternsAreDefensivelyCopied() {
        var patterns = new java.util.HashMap<String, List<OntologyPattern>>();
        patterns.put("works at", List.of(new OntologyPattern("Person", "Organization")));
        var overlay = new OntologyExtractionOverlay(patterns, Map.of(), ExtractionMode.PREFER);

        patterns.put("lives in", List.of(new OntologyPattern("Person", "City")));

        assertThat(overlay.patterns()).containsOnlyKeys("works at");
    }

    @Test
    void defaultsToPreferMode() {
        var overlay = OntologyExtractionOverlay.empty();

        assertThat(overlay.mode()).isEqualTo(ExtractionMode.PREFER);
    }

    @Test
    void withModeReturnsOverlayWithNewMode() {
        var overlay = OntologyExtractionOverlay.empty().withMode(ExtractionMode.STRICT);

        assertThat(overlay.mode()).isEqualTo(ExtractionMode.STRICT);
    }
}
