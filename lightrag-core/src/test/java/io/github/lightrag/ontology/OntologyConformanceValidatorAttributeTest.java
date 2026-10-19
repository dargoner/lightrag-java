package io.github.lightrag.ontology;

import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyConformanceValidatorAttributeTest {

    private static final Ontology ONTOLOGY = new Ontology(
        List.of(
            new OntologyEntityType("Person", "A person", List.of(), null),
            new OntologyEntityType("Organization", "An organization", List.of(), null)
        ),
        List.of(new OntologyRelationType(
            "works at",
            "employment",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(),
            null
        )),
        null,
        Map.of()
    );

    @Test
    void forwardsAttributeDiagnosticsToUnmatchedConsumer() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);
        var diagnostics = List.of(
            new UnmatchedLabel(
                UnmatchedLabel.Kind.ATTRIBUTE,
                "age",
                "chunk-1",
                UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE,
                "person"),
            new UnmatchedLabel(
                UnmatchedLabel.Kind.ATTRIBUTE,
                "",
                "chunk-1",
                UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED,
                "person"));

        validator.onAttributeDiagnostics(diagnostics);

        assertThat(unmatched).containsExactlyElementsOf(diagnostics);
    }

    @Test
    void preferRebuildAsOtherClearsAttributes() {
        var validator = new OntologyConformanceValidator(
            ONTOLOGY, ignored -> {}, false, ExtractionMode.PREFER);
        var entity = new ExtractedEntity(
            "Unknown", "MysteryType", "", List.of(), Map.of("declared", "value"));

        var result = validator.validate(
            "chunk-1",
            "text",
            new ExtractionResult(List.of(entity), List.of(), List.of())
        );

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Unknown", "Other", "", List.of(), Map.of()));
    }

    @Test
    void knownEntityAndRelationKeepBoundAttributes() {
        var validator = new OntologyConformanceValidator(ONTOLOGY);
        var entity = new ExtractedEntity(
            "Alice", "Person", "", List.of(), Map.of("age", 30L));
        var relation = new ExtractedRelation(
            "Alice", "Acme", "works at", "", 1.0d, Map.of("since", "2020-01-01"));

        var result = validator.validate(
            "chunk-1",
            "text",
            new ExtractionResult(
                List.of(entity, new ExtractedEntity("Acme", "Organization", "", List.of())),
                List.of(relation),
                List.of())
        );

        assertThat(result.entities()).contains(entity);
        assertThat(result.relations()).containsExactly(relation);
    }

    @Test
    void openModeKeepsBoundAttributesUnchanged() {
        var validator = new OntologyConformanceValidator(
            ONTOLOGY, ignored -> {}, false, ExtractionMode.OPEN);
        var entity = new ExtractedEntity(
            "Unknown", "MysteryType", "", List.of(), Map.of("declared", "value"));

        var result = validator.validate(
            "chunk-1",
            "text",
            new ExtractionResult(List.of(entity), List.of(), List.of())
        );

        assertThat(result.entities()).containsExactly(entity);
    }

    @Test
    void validateDoesNotForwardAttributeDiagnosticsAgain() {
        var unmatched = new ArrayList<UnmatchedLabel>();
        var validator = new OntologyConformanceValidator(ONTOLOGY, unmatched::add, false);

        validator.validate(
            "chunk-1",
            "text",
            new ExtractionResult(
                List.of(new ExtractedEntity("Alice", "Person", "", List.of())),
                List.of(),
                List.of())
        );

        assertThat(unmatched).noneMatch(diagnostic ->
            diagnostic.kind() == UnmatchedLabel.Kind.ATTRIBUTE);
    }
}
