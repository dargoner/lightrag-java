package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyAttributeProjectorTest {

    private static Ontology ontology() {
        OntologyEntityType person = new OntologyEntityType(
            "Person",
            null,
            List.of(
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("Nick Name", OntologyPropertyType.STRING, null, null)
            ),
            null
        );
        OntologyRelationType likes = new OntologyRelationType(
            "Likes",
            null,
            List.of(),
            List.of(new OntologyProperty("Score", OntologyPropertyType.FLOAT, null, null)),
            null
        );
        OntologyRelationType worksAt = new OntologyRelationType(
            "Works At",
            null,
            List.of(),
            List.of(
                new OntologyProperty("Score", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)
            ),
            null
        );
        return new Ontology(List.of(person), List.of(likes, worksAt), null, Map.of());
    }

    @Test
    void projectsEntityOntoTheFinalTypeAndDropsUndeclaredKeys() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("age", 3L);
        attributes.put("unknown", "x");
        attributes.put("nick name", "Al");

        Map<String, Object> projected = OntologyAttributeProjector.projectEntity(
            "PERSON", attributes, ontology());

        assertThat(projected).containsExactly(
            Map.entry("age", 3L),
            Map.entry("nick name", "Al")
        );
    }

    @Test
    void unknownFinalTypeProjectsNothing() {
        assertThat(OntologyAttributeProjector.projectEntity(
            "Alien", Map.of("age", 3L), ontology())).isEmpty();
        assertThat(OntologyAttributeProjector.projectEntity(
            null, Map.of("age", 3L), ontology())).isEmpty();
        assertThat(OntologyAttributeProjector.projectEntity(
            "Person", null, ontology())).isEmpty();
    }

    @Test
    void nullOntologyPassesAttributesThroughUnchanged() {
        Map<String, Object> attributes = Map.of("age", 3L, "nick name", "Al");

        assertThat(OntologyAttributeProjector.projectEntity("Person", attributes, null))
            .isEqualTo(attributes);
        assertThat(OntologyAttributeProjector.projectRelation("works_with", attributes, null))
            .isEqualTo(attributes);
    }

    @Test
    void reCoercesValuesAgainstTheFinalDeclaration() {
        Map<String, Object> projected = OntologyAttributeProjector.projectEntity(
            "Person",
            Map.of("age", 3.9d, "nick name", 7L),
            ontology()
        );

        assertThat(projected).containsExactly(
            Map.entry("age", 3L),
            Map.entry("nick name", "7")
        );
    }

    @Test
    void dropsValuesThatTheFinalTypeCannotRepresent() {
        Map<String, Object> projected = OntologyAttributeProjector.projectEntity(
            "Person",
            Map.of("age", "abc", "nick name", "Al"),
            ontology()
        );

        assertThat(projected).containsExactly(Map.entry("nick name", "Al"));
    }

    @Test
    void dropsIdentityDuplicatesWithDifferentValues() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("Age", 1L);
        attributes.put("age", 2L);

        assertThat(OntologyAttributeProjector.projectEntity("Person", attributes, ontology()))
            .isEmpty();
    }

    @Test
    void projectsRelationOntoTheFinalKeywordSet() {
        Map<String, Object> projected = OntologyAttributeProjector.projectRelation(
            "Works At",
            Map.of("score", 1.9d, "since", "2024-01-02T03:04:05Z"),
            ontology()
        );

        assertThat(projected).containsExactly(
            Map.entry("score", 1L),
            Map.entry("since", "2024-01-02")
        );
    }

    @Test
    void dropsRelationPropertiesDeclaredWithDifferentTypesAcrossHits() {
        assertThat(OntologyAttributeProjector.projectRelation(
            "Likes, Works At", Map.of("score", 1L), ontology())).isEmpty();
    }

    @Test
    void relationWithoutHitsProjectsNothing() {
        assertThat(OntologyAttributeProjector.projectRelation(
            "unknown", Map.of("score", 1L), ontology())).isEmpty();
        assertThat(OntologyAttributeProjector.projectRelation(
            null, Map.of("score", 1L), ontology())).isEmpty();
    }

    @Test
    void appliesSemanticCapsDuringProjection() {
        List<OntologyProperty> properties = new ArrayList<>();
        for (int i = 0; i < AttributeValues.MAX_KEYS + 5; i++) {
            properties.add(new OntologyProperty(
                String.format("P%02d", i), OntologyPropertyType.STRING, null, null));
        }
        Ontology wide = new Ontology(
            List.of(new OntologyEntityType("Wide", null, properties, null)),
            List.of(),
            null,
            Map.of()
        );
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (int i = 0; i < AttributeValues.MAX_KEYS + 5; i++) {
            attributes.put(String.format("p%02d", i), "v" + i);
        }

        Map<String, Object> projected = OntologyAttributeProjector.projectEntity(
            "Wide", attributes, wide);

        assertThat(projected).hasSize(AttributeValues.MAX_KEYS);
        assertThat(projected).containsKey("p63").doesNotContainKey("p64");
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> projected.put("x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
