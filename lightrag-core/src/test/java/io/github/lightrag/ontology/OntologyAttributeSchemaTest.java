package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyAttributeSchemaTest {

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
        OntologyRelationType worksAt = new OntologyRelationType(
            "Works At",
            null,
            List.of(),
            List.of(new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)),
            null
        );
        OntologyRelationType likes = new OntologyRelationType(
            "Likes",
            null,
            List.of(),
            List.of(new OntologyProperty("Score", OntologyPropertyType.FLOAT, null, null)),
            null
        );
        return new Ontology(List.of(person), List.of(worksAt, likes), null, Map.of());
    }

    @Test
    void derivesIdentityIndexesFromCanonicalOntology() {
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(ontology());

        assertThat(schema.entityTypes()).containsOnlyKeys("person");
        assertThat(schema.relationTypes()).containsOnlyKeys("likes", "works at");
        assertThat(schema.entityTypes().get("person"))
            .extracting(OntologyProperty::identityId)
            .containsExactly("age", "nick name");
        assertThat(schema.isEmpty()).isFalse();
    }

    @Test
    void looksUpByAnyLabelSpelling() {
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(ontology());

        assertThat(schema.entityProperties("PERSON"))
            .extracting(OntologyProperty::name)
            .containsExactly("age", "nick name");
        assertThat(schema.entityProperties(" Person "))
            .hasSize(2);
        assertThat(schema.relationProperties("WORKS  AT"))
            .extracting(OntologyProperty::name)
            .containsExactly("since");
        assertThat(schema.entityProperties("Unknown")).isEmpty();
        assertThat(schema.entityProperties(null)).isEmpty();
        assertThat(schema.entityProperties("   ")).isEmpty();
        assertThat(schema.relationProperties("")).isEmpty();
    }

    @Test
    void resolvesRelationKeywordsByIdentityDeduplicatesAndSorts() {
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(ontology());

        assertThat(schema.relationTypesForKeywords(" Likes , WORKS AT , Likes, unknown "))
            .containsExactly("likes", "works at");
        assertThat(schema.relationTypesForKeywords("LIKES")).containsExactly("likes");
        assertThat(schema.relationTypesForKeywords("")).isEmpty();
        assertThat(schema.relationTypesForKeywords(" , , ")).isEmpty();
        assertThat(schema.relationTypesForKeywords(null)).isEmpty();
    }

    @Test
    void emptySchemaIsEmpty() {
        OntologyAttributeSchema empty = OntologyAttributeSchema.empty();

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.entityProperties("Person")).isEmpty();
        assertThat(empty.relationTypesForKeywords("likes")).isEmpty();
        assertThat(OntologyAttributeSchema.from(
            new Ontology(List.of(), List.of(), null, Map.of())).isEmpty()).isTrue();
    }

    @Test
    void directConstructionNormalizesKeysAndDeduplicatesProperties() {
        OntologyAttributeSchema schema = new OntologyAttributeSchema(
            Map.of("Person", List.of(
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("age", OntologyPropertyType.INTEGER, null, null)
            )),
            Map.of("Works At", List.of()),
            Map.of("Likes", Set.of("Works At", "likes"))
        );

        assertThat(schema.entityTypes()).containsOnlyKeys("person");
        assertThat(schema.entityProperties("person")).hasSize(1);
        assertThat(schema.relationKeywords().get("likes"))
            .containsExactly("likes", "works at");
        assertThat(schema.relationTypesForKeywords("likes"))
            .containsExactly("likes", "works at");
    }

    @Test
    void reservedKernelPropertiesAreNeverMaterialized() {
        OntologyEntityType person = new OntologyEntityType(
            "Person",
            null,
            List.of(
                new OntologyProperty("Name", OntologyPropertyType.STRING, null, null),
                new OntologyProperty("Type", OntologyPropertyType.STRING, null, null),
                new OntologyProperty("Description", OntologyPropertyType.STRING, null, null),
                new OntologyProperty("Aliases", OntologyPropertyType.LIST, null, null),
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null)
            ),
            null
        );
        OntologyRelationType worksAt = new OntologyRelationType(
            "Works At",
            null,
            List.of(),
            List.of(
                new OntologyProperty("Weight", OntologyPropertyType.FLOAT, null, null),
                new OntologyProperty("Relationship Description", OntologyPropertyType.STRING, null, null),
                new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)
            ),
            null
        );
        OntologyAttributeSchema schema = OntologyAttributeSchema.from(
            new Ontology(List.of(person), List.of(worksAt), null, Map.of())
        );

        assertThat(schema.entityProperties("person"))
            .extracting(OntologyProperty::identityId)
            .containsExactly("age");
        assertThat(schema.relationProperties("works at"))
            .extracting(OntologyProperty::identityId)
            .containsExactly("since");
    }

    @Test
    void directConstructionAlsoFiltersReservedKernelProperties() {
        OntologyAttributeSchema schema = new OntologyAttributeSchema(
            Map.of("Person", List.of(
                new OntologyProperty("name", OntologyPropertyType.STRING, null, null),
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null)
            )),
            Map.of("Works At", List.of(
                new OntologyProperty("weight", OntologyPropertyType.FLOAT, null, null),
                new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)
            )),
            Map.of()
        );

        assertThat(schema.entityProperties("person"))
            .extracting(OntologyProperty::identityId)
            .containsExactly("age");
        assertThat(schema.relationProperties("works at"))
            .extracting(OntologyProperty::identityId)
            .containsExactly("since");
    }

    @Test
    void relationKeywordsCanPointOneKeywordAtSeveralRelationTypes() {
        OntologyAttributeSchema schema = new OntologyAttributeSchema(
            Map.of(),
            Map.of(
                "Likely", List.of(new OntologyProperty(
                    "score", OntologyPropertyType.FLOAT, null, null)),
                "Loves", List.of(new OntologyProperty(
                    "score", OntologyPropertyType.INTEGER, null, null))
            ),
            Map.of("like", Set.of("Likely", "Loves"))
        );

        assertThat(schema.relationTypesForKeywords("like"))
            .containsExactly("likely", "loves");
    }
}
