package io.github.lightrag.ontology;

import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyConformanceValidatorTest {

    private static final Ontology ONTOLOGY = new Ontology(
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
        java.util.Map.of()
    );

    @Test
    void keepsEntitiesWithDeclaredTypeAndOtherFallback() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Something", "Other", "", List.of()),
                    new ExtractedEntity("Unknown", "UnknownType", "", List.of())
                ),
                List.of(),
                List.of()
            )
        );

        assertThat(result.entities()).extracting(ExtractedEntity::name)
            .containsExactly("Alice", "Something");
    }

    @Test
    void dropsRelationsIncidentOnDroppedEntity() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Unknown", "UnknownType", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Unknown", "works at", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.entities()).hasSize(1);
        assertThat(result.relations()).isEmpty();
    }

    @Test
    void keepsRelationWithKnownKeywordAndMatchingPattern() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", "works at", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void dropsRelationWithPatternMismatch() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Acme", "Organization", "", List.of()),
                    new ExtractedEntity("Beta", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Acme", "Beta", "works at", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
    }

    @Test
    void dropsRelationWithUnknownKeyword() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", "unknown relation", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
    }

    @Test
    void toleratesReversedPatternDirection() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Acme", "Organization", "", List.of()),
                    new ExtractedEntity("Alice", "Person", "", List.of())
                ),
                List.of(new ExtractedRelation("Acme", "Alice", "works at", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void reportsUnmatchedLabelsViaConsumer() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);

        validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("X", "UnknownType", "", List.of()),
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", "unknown", "", 1.0)),
                List.of()
            )
        );

        assertThat(unmatched).anyMatch(u ->
            u.kind() == UnmatchedLabel.Kind.ENTITY
                && u.rawLabel().equals("UnknownType")
                && u.reason() == UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE);
        assertThat(unmatched).anyMatch(u ->
            u.kind() == UnmatchedLabel.Kind.RELATION_KEYWORD
                && u.rawLabel().equals("unknown")
                && u.reason() == UnmatchedLabel.Reason.UNKNOWN_RELATION_KEYWORD);
    }

    @Test
    void dropOnUnknownKeywordRemovesMixedKeywordRelation() {
        var validator = new OntologyConformanceValidator(ONTOLOGY, ignored -> {}, true);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", "works at, unknown", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
    }

    @Test
    void dropsEntityWithBlankTypeAndReportsUnmatched() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);

        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(new ExtractedEntity("Alice", "", "", List.of())),
                List.of(),
                List.of()
            )
        );

        assertThat(result.entities()).isEmpty();
        assertThat(unmatched).anyMatch(u ->
            u.kind() == UnmatchedLabel.Kind.ENTITY
                && u.rawLabel().isEmpty()
                && u.reason() == UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE);
    }

    @Test
    void dropsRelationWithEmptyKeywords() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", ", ,", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
    }

    @Test
    void dropsEntityTypeWithUnicodeWhitespaceWithoutThrowing() {
        for (String whitespaceType : List.of("   ", "\u00A0", "\u2007", "\u202F")) {
            var unmatched = new ArrayList<UnmatchedLabel>();
            var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);
            var entity = new ExtractedEntity("Alice", whitespaceType, "", List.of());

            var result = validator.validate(
                "c1",
                "text",
                new ExtractionResult(List.of(entity), List.of(), List.of())
            );

            assertThat(result.entities()).isEmpty();
            assertThat(unmatched).anyMatch(u ->
                u.kind() == UnmatchedLabel.Kind.ENTITY
                    && u.rawLabel().equals(entity.type())
                    && u.reason() == UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE);
        }
    }

    @Test
    void dropsRelationWithOnlyWhitespaceKeywordsWithoutThrowing() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", ",\u00A0,   ", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
        assertThat(unmatched).anyMatch(u ->
            u.kind() == UnmatchedLabel.Kind.RELATION
                && u.reason() == UnmatchedLabel.Reason.UNKNOWN_RELATION_TYPE);
    }

    @Test
    void dropsRelationWithUnicodeWhitespaceKeywordsWithoutThrowing() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(
                    new ExtractedEntity("Alice", "Person", "", List.of()),
                    new ExtractedEntity("Acme", "Organization", "", List.of())
                ),
                List.of(new ExtractedRelation("Alice", "Acme", "works at,\u00A0", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).hasSize(1);
    }

    @Test
    void dropsRelationWithMissingEndpoint() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);
        var result = validator.validate(
            "c1",
            "text",
            new ExtractionResult(
                List.of(new ExtractedEntity("Alice", "Person", "", List.of())),
                List.of(new ExtractedRelation("Alice", "Missing", "works at", "", 1.0)),
                List.of()
            )
        );

        assertThat(result.relations()).isEmpty();
        assertThat(unmatched).anyMatch(u ->
            u.kind() == UnmatchedLabel.Kind.RELATION
                && u.reason() == UnmatchedLabel.Reason.DANGLING_ENDPOINT);
    }
}
