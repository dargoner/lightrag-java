package io.github.lightrag.ontology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OntologyAttributeBinderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final OntologyAttributeBinder BINDER = new OntologyAttributeBinder();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Ontology ontology() {
        OntologyEntityType person = new OntologyEntityType(
            "Person",
            "A person",
            List.of(
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("Nick Name", OntologyPropertyType.STRING, null, null)
            ),
            null
        );
        OntologyRelationType likes = new OntologyRelationType(
            "Likes",
            "Liking",
            List.of(),
            List.of(new OntologyProperty("Score", OntologyPropertyType.FLOAT, null, null)),
            null
        );
        OntologyRelationType worksAt = new OntologyRelationType(
            "Works At",
            "Employment",
            List.of(),
            List.of(
                new OntologyProperty("Score", OntologyPropertyType.INTEGER, null, null),
                new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)
            ),
            null
        );
        return new Ontology(List.of(person), List.of(likes, worksAt), null, Map.of());
    }

    private static OntologyAttributeSchema schema() {
        return OntologyAttributeSchema.from(ontology());
    }

    @Test
    void duplicateRulingHappensAfterCoercionAndIsOrderIndependent() {
        BoundAttributes forward = BINDER.bindEntity(
            schema(), "chunk-1", "Person", json("{\"Age\":\"1\",\" age \":\"1.0\"}"));
        BoundAttributes backward = BINDER.bindEntity(
            schema(), "chunk-1", "Person", json("{\" age \":\"1.0\",\"Age\":\"1\"}"));

        assertThat(forward.diagnostics()).isEmpty();
        assertThat(forward.values()).containsExactly(Map.entry("age", 1L));
        assertThat(backward).isEqualTo(forward);
    }

    @Test
    void conflictingValuesForOneIdentityDropTheKey() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-1", "Person", json("{\"Age\":1,\" Age \":2}"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::kind, UnmatchedLabel::rawLabel,
                UnmatchedLabel::chunkId, UnmatchedLabel::reason, UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                UnmatchedLabel.Kind.ATTRIBUTE,
                "Age",
                "chunk-1",
                UnmatchedLabel.Reason.ATTRIBUTE_DUPLICATE_KEY,
                "person"
            ));
    }

    @Test
    void whitespaceVariantDuplicatesUseTheSmallestRawSpelling() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-1", "Person", json(
                "{\"Nick  Name\":\"A\",\"nick name\":\"B\"}"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason)
            .containsExactly(tuple("Nick  Name", UnmatchedLabel.Reason.ATTRIBUTE_DUPLICATE_KEY));
    }

    @Test
    void unknownEntityTypeDropsAllAttributesWithUndeclaredDiagnostics() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-2", "Alien", json("{\"Age\":1}"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason, UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                "Age", UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE, "alien"));
    }

    @Test
    void undeclaredKeysAreDroppedButDeclaredKeysSurvive() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-3", "PERSON",
            json("{\"Age\":1,\"Zeta\":2,\"Alpha\":3}"));

        assertThat(bound.values()).containsExactly(Map.entry("age", 1L));
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason)
            .containsExactly(
                tuple("Alpha", UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE),
                tuple("Zeta", UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE)
            );
    }

    @Test
    void coerceFailureDropsOnlyThatKey() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-4", "Person", json("{\"Age\":\"abc\",\"Nick Name\":\"Al\"}"));

        assertThat(bound.values()).containsExactly(Map.entry("nick name", "Al"));
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason, UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                "Age", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE, "person"));
    }

    @Test
    void relationPropertiesFromDifferentHitTypesConflict() {
        BoundAttributes bound = BINDER.bindRelation(
            schema(),
            "chunk-5",
            "Likes, Works At",
            json("{\"Score\":1,\"Since\":\"2024-01-02\"}")
        );

        assertThat(bound.values()).containsExactly(Map.entry("since", "2024-01-02"));
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason, UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                "Score",
                UnmatchedLabel.Reason.ATTRIBUTE_TYPE_CONFLICT,
                "likes"
            ));
    }

    @Test
    void relationWithoutHitTypeDropsAllAttributes() {
        BoundAttributes bound = BINDER.bindRelation(
            schema(), "chunk-6", "unknown keyword", json("{\"Score\":1}"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason, UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                "Score", UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE, ""));
    }

    @Test
    void blankKeyAndWholeObjectLimitKeepEmptyRawLabelWithoutThrowing() {
        BoundAttributes blankKey = BINDER.bindEntity(
            schema(), "chunk-7", "Person", json("{\"  \":1}"));
        assertThat(blankKey.values()).isEmpty();
        assertThat(blankKey.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason)
            .containsExactly(tuple("", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY));

        ObjectNode oversized = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < AttributeValues.MAX_RAW_CANDIDATES + 1; i++) {
            oversized.put("key" + i, i);
        }
        BoundAttributes limited = BINDER.bindEntity(schema(), "chunk-7", "Person", oversized);
        assertThat(limited.values()).isEmpty();
        assertThat(limited.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason)
            .containsExactly(tuple("", UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED));
    }

    @Test
    void blankOwnerLabelIsOrderedViaSentinelWithoutThrowing() {
        BoundAttributes bound = BINDER.bindEntity(schema(), "chunk-8", "   ", json("{\"Age\":1}"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::ownerLabel,
                UnmatchedLabel::reason)
            .containsExactly(tuple("Age", "", UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE));
        assertThat(OntologyAttributeBinder.diagnosticSortKey("")).isEqualTo("<empty>");
        assertThat(OntologyAttributeBinder.diagnosticSortKey(" Person ")).isEqualTo("person");
    }

    @Test
    void missingOrEmptySchemaBindsNothing() {
        assertThat(BINDER.bindEntity(null, "c", "Person", json("{\"Age\":1}")).values()).isEmpty();
        assertThat(BINDER.bindEntity(
            OntologyAttributeSchema.empty(), "c", "Person", json("{\"Age\":1}")).diagnostics())
            .isEmpty();
        assertThat(BINDER.bindRelation(
            OntologyAttributeSchema.empty(), "c", "Likes", json("{\"Score\":1}")).values())
            .isEmpty();
    }

    @Test
    void nonObjectAttributesProduceAnInvalidValueDiagnostic() {
        BoundAttributes bound = BINDER.bindEntity(schema(), "c", "Person", json("[1,2]"));

        assertThat(bound.values()).isEmpty();
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel, UnmatchedLabel::reason,
                UnmatchedLabel::ownerLabel)
            .containsExactly(tuple(
                "", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE, "person"));
    }

    @Test
    void semanticCapsRunAfterBindingAndReportDroppedKeys() {
        List<OntologyProperty> properties = new ArrayList<>();
        for (int i = 0; i < AttributeValues.MAX_KEYS + 6; i++) {
            properties.add(new OntologyProperty(
                String.format("P%02d", i), OntologyPropertyType.STRING, null, null));
        }
        Ontology wide = new Ontology(
            List.of(new OntologyEntityType("Wide", null, properties, null)),
            List.of(),
            null,
            Map.of()
        );
        ObjectNode attributes = JsonNodeFactory.instance.objectNode();
        for (int i = 0; i < AttributeValues.MAX_KEYS + 6; i++) {
            attributes.put(String.format("P%02d", i), "v" + i);
        }

        BoundAttributes bound = BINDER.bindEntity(
            OntologyAttributeSchema.from(wide), "chunk-9", "Wide", attributes);

        assertThat(bound.values()).hasSize(AttributeValues.MAX_KEYS);
        assertThat(bound.values()).containsKey("p63");
        assertThat(bound.values()).doesNotContainKey("p64");
        assertThat(bound.diagnostics())
            .hasSize(6)
            .allSatisfy(diagnostic -> {
                assertThat(diagnostic.kind()).isEqualTo(UnmatchedLabel.Kind.ATTRIBUTE);
                assertThat(diagnostic.reason())
                    .isEqualTo(UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED);
                assertThat(diagnostic.ownerLabel()).isEqualTo("wide");
            });
        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel)
            .containsExactly("P64", "P65", "P66", "P67", "P68", "P69");
    }

    @Test
    void resultKeysAreIdentitiesAndSorted() {
        BoundAttributes bound = BINDER.bindEntity(
            schema(), "chunk-10", "PERSON",
            json("{\"Nick Name\":\"Al\",\"Age\":3}"));

        assertThat(bound.values().keySet()).containsExactly("age", "nick name");
    }

    @Test
    void diagnosticsAreSortedByOwnerThenRawLabel() {
        BoundAttributes bound = BINDER.bindRelation(
            schema(), "chunk-11", "Likes", json("{\"Zeta\":1,\"Score\":\"x\",\"Alpha\":2}"));

        assertThat(bound.diagnostics())
            .extracting(UnmatchedLabel::rawLabel)
            .containsExactly("Alpha", "Score", "Zeta");
    }
}
