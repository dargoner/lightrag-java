package io.github.lightrag.indexing;

import io.github.lightrag.api.KgExtractionValidator;
import io.github.lightrag.indexing.refinement.RefinedEntityPatch;
import io.github.lightrag.indexing.refinement.RefinedRelationPatch;
import io.github.lightrag.indexing.refinement.RefinementScope;
import io.github.lightrag.indexing.refinement.RefinementWindow;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyAttributeSchema;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeExtractorAttributeTest {

    @Test
    void invalidAttributesNeverDropTheEntityOrRelation() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {"age": "not-a-number", "unknown": 1}
                }
              ],
              "relations": [
                {
                  "source_entity": "Alice",
                  "target_entity": "Acme",
                  "relationship_keywords": "Works At",
                  "relationship_description": "employment",
                  "attributes": {"since": "not-a-date"}
                }
              ]
            }
            """);
        var validator = new RecordingValidator();

        var result = extractor(chatModel, ontology(), validator).extract(chunk("chunk-1", "Alice works at Acme"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "Person", "Researcher", List.of(), Map.of()));
        assertThat(result.relations()).containsExactly(
            new ExtractedRelation("Alice", "Acme", "Works At", "employment", 1.0d, Map.of()));
        assertThat(validator.attributeForwardCount).isEqualTo(1);
        assertThat(validator.forwarded).extracting(UnmatchedLabel::reason).containsExactlyInAnyOrder(
            UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE,
            UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE,
            UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE);
    }

    @Test
    void coercesPrimitiveDateAndListValuesOntoRecords() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {
                    "age": "42",
                    "birth date": "2020-05-04T10:15:30Z",
                    "tags": ["a", "b"],
                    "nick name": 7
                  }
                }
              ],
              "relations": [
                {
                  "source_entity": "Alice",
                  "target_entity": "Acme",
                  "relationship_keywords": "Works At",
                  "relationship_description": "employment",
                  "attributes": {"since": "2021-01-02T00:00:00+01:00"}
                }
              ]
            }
            """);

        var result = extractor(chatModel, ontology(), null).extract(chunk("chunk-1", "Alice works at Acme"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity(
                "Alice",
                "Person",
                "Researcher",
                List.of(),
                Map.of(
                    "age", 42L,
                    "birth date", "2020-05-04",
                    "tags", List.of("a", "b"),
                    "nick name", "7")));
        assertThat(result.relations()).containsExactly(
            new ExtractedRelation(
                "Alice", "Acme", "Works At", "employment", 1.0d, Map.of("since", "2021-01-02")));
    }

    @Test
    void forwardsSortedDeduplicatedDiagnosticsExactlyOnceAcrossGleaning() {
        var badResponse = """
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {"age": "bad", "unknown": 1}
                }
              ],
              "relations": []
            }
            """;
        var chatModel = new RecordingChatModel(badResponse, badResponse);
        var validator = new RecordingValidator();

        extractorWithGleaning(chatModel, validator).extract(chunk("chunk-1", "Alice works at Acme"));

        assertThat(validator.attributeForwardCount).isEqualTo(1);
        assertThat(validator.forwarded).containsExactly(
            new UnmatchedLabel(
                UnmatchedLabel.Kind.ATTRIBUTE,
                "age",
                "chunk-1",
                UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE,
                "person"),
            new UnmatchedLabel(
                UnmatchedLabel.Kind.ATTRIBUTE,
                "unknown",
                "chunk-1",
                UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE,
                "person"));
    }

    @Test
    void deduplicatesTheSameDiagnosticAcrossRawSpellingsAndGleaningPasses() {
        var primary = """
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {"Age": "bad"}
                }
              ],
              "relations": []
            }
            """;
        var gleaning = """
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {"age ": "still bad"}
                }
              ],
              "relations": []
            }
            """;
        var chatModel = new RecordingChatModel(primary, gleaning);
        var validator = new RecordingValidator();

        extractorWithGleaning(chatModel, validator).extract(chunk("chunk-1", "Alice works at Acme"));

        assertThat(validator.attributeForwardCount).isEqualTo(1);
        assertThat(validator.forwarded).containsExactly(new UnmatchedLabel(
            UnmatchedLabel.Kind.ATTRIBUTE,
            "Age",
            "chunk-1",
            UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE,
            "person"));
    }

    @Test
    void gleaningMergeCombinesAttributesWithIncomingValuesWinning() {
        var chatModel = new RecordingChatModel(
            """
            {
              "entities": [
                {"name": "Alice", "type": "Person", "description": "first", "aliases": [], "attributes": {"age": 30}}
              ],
              "relations": []
            }
            """,
            """
            {
              "entities": [
                {"name": "Alice", "type": "Person", "description": "second", "aliases": ["Al"], "attributes": {"age": 31, "nick name": "Al"}}
              ],
              "relations": []
            }
            """);

        var result = extractorWithGleaning(chatModel, null).extract(chunk("chunk-1", "Alice works at Acme"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity(
                "Alice",
                "Person",
                "second",
                List.of("Al"),
                Map.of("age", 31L, "nick name", "Al")));
    }

    @Test
    void sanitizeRebuildKeepsAttributes() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": ["Bob"],
                  "attributes": {"age": 30}
                }
              ],
              "relations": [
                {
                  "source_entity": "Alice",
                  "target_entity": "Bob",
                  "relationship_keywords": "Works At",
                  "relationship_description": "employment"
                }
              ]
            }
            """);

        var result = extractor(chatModel, ontology(), null).extract(chunk("chunk-1", "Alice works with Bob"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "Person", "Researcher", List.of(), Map.of("age", 30L)));
    }

    @Test
    void promptWithoutDeclarationsIsIdenticalToAnExplicitEmptySchemaAndOmitsAttributes() {
        var withoutSchemaModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var withEmptySchemaModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var withoutSchema = extractorModel(withoutSchemaModel, null);
        var withEmptySchema = new KnowledgeExtractor(
            withEmptySchemaModel,
            0,
            10_000,
            "English",
            List.of("Person", "Organization"),
            List.of("works at"),
            List.of(),
            false,
            100,
            40,
            null,
            true,
            OntologyExtractionOverlay.empty(),
            OntologyAttributeSchema.empty()
        );

        withoutSchema.extract(chunk("chunk-1", "text"));
        withEmptySchema.extract(chunk("chunk-1", "text"));

        var prompt = withoutSchemaModel.requests().get(0).systemPrompt();
        assertThat(withEmptySchemaModel.requests().get(0).systemPrompt()).isEqualTo(prompt);
        assertThat(prompt).doesNotContain("\"attributes\"");
        assertThat(prompt).contains("\"aliases\": [\"Alias if explicitly stated\"]\n");
    }

    @Test
    void promptRendersSeparateEntityAndRelationSectionsForCollidingLabels() {
        var collision = new Ontology(
            List.of(new OntologyEntityType(
                "Person",
                "A person",
                List.of(new OntologyProperty("Age", OntologyPropertyType.INTEGER, "Age in years", null)),
                null)),
            List.of(new OntologyRelationType(
                "Person",
                "A relation sharing the entity label",
                List.of(),
                List.of(new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)),
                null)),
            null,
            Map.of());
        var chatModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var extractor = extractorModel(chatModel, collision);

        extractor.extract(chunk("chunk-1", "text"));

        var prompt = chatModel.requests().get(0).systemPrompt();
        assertThat(prompt).contains("Entity type attribute declarations");
        assertThat(prompt).contains("Relation type attribute declarations");
        assertThat(prompt).contains("- age (integer): Age in years");
        assertThat(prompt).contains("- since (date)");
        assertThat(countOccurrences(prompt, "\n             - person:")).isEqualTo(2);
        assertThat(countOccurrences(prompt, "\"attributes\": {\"property\": \"value\"}")).isEqualTo(2);
        assertThat(prompt).doesNotContain("Person attributes:");
    }

    @Test
    void promptIsIdenticalForDisplayAndRestoredCanonicalOntology() {
        var displayOntology = new Ontology(
            List.of(new OntologyEntityType(
                "Person Type",
                "A person",
                List.of(new OntologyProperty("Age", OntologyPropertyType.INTEGER, null, null)),
                null)),
            List.of(new OntologyRelationType(
                "Works At",
                "Employment",
                List.of(),
                List.of(new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)),
                null)),
            null,
            Map.of());
        var restoredOntology = displayOntology.toCanonical();

        var firstModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var restoredModel = new RecordingChatModel("""
            {
              "entities": [],
              "relations": []
            }
            """);
        var first = extractorModel(firstModel, displayOntology);
        var restored = extractorModel(restoredModel, restoredOntology);

        first.extract(chunk("chunk-1", "text"));
        restored.extract(chunk("chunk-1", "text"));

        var prompt = firstModel.requests().get(0).systemPrompt();
        assertThat(restoredModel.requests().get(0).systemPrompt()).isEqualTo(prompt);
        assertThat(prompt).contains("\n             - person type:");
        assertThat(prompt).contains("\n             - works at:");
        assertThat(prompt).doesNotContain("Person Type:");
    }

    @Test
    void windowCandidatesCarryBoundAttributesAndForwardDiagnosticsOnce() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "Person",
                  "description": "Researcher",
                  "aliases": [],
                  "supportingChunkIndexes": [0],
                  "attributes": {"age": "7"}
                }
              ],
              "relations": [
                {
                  "source_entity": "Alice",
                  "target_entity": "Acme",
                  "relationship_keywords": "Works At",
                  "relationship_description": "employment",
                  "supportingChunkIndexes": [0, 1],
                  "attributes": {"since": "2022-02-03", "bad": "x"}
                }
              ],
              "warnings": []
            }
            """);
        var validator = new RecordingValidator();
        var extractor = extractor(chatModel, ontology(), validator);
        var window = new RefinementWindow(
            "doc-1",
            List.of(chunk("chunk-1", "Alice works"), chunk("chunk-2", "at Acme")),
            0,
            RefinementScope.ADJACENT,
            16
        );

        var result = extractor.extractWindow(window);

        assertThat(result.entityPatches()).containsExactly(
            new RefinedEntityPatch(
                new ExtractedEntity("Alice", "Person", "Researcher", List.of(), Map.of("age", 7L)),
                List.of("chunk-1")));
        assertThat(result.relationPatches()).containsExactly(
            new RefinedRelationPatch(
                new ExtractedRelation(
                    "Alice", "Acme", "works_at", "employment", 1.0d, Map.of("since", "2022-02-03")),
                List.of("chunk-1", "chunk-2")));
        assertThat(validator.attributeForwardCount).isEqualTo(1);
        assertThat(validator.forwarded).containsExactly(new UnmatchedLabel(
            UnmatchedLabel.Kind.ATTRIBUTE,
            "bad",
            "chunk-1",
            UnmatchedLabel.Reason.UNDECLARED_ATTRIBUTE,
            "works at"));
    }

    @Test
    void noSchemaDropsAttributesWithoutForwardingDiagnostics() {
        var chatModel = new RecordingChatModel("""
            {
              "entities": [
                {
                  "name": "Alice",
                  "type": "person",
                  "description": "Researcher",
                  "aliases": [],
                  "attributes": {"anything": "value"}
                }
              ],
              "relations": []
            }
            """);
        var validator = new RecordingValidator();
        var extractor = new KnowledgeExtractor(
            chatModel,
            0,
            10_000,
            "English",
            List.of("person"),
            List.of(),
            List.of(),
            false,
            100,
            40,
            validator,
            true
        );

        var result = extractor.extract(chunk("chunk-1", "text"));

        assertThat(result.entities()).containsExactly(
            new ExtractedEntity("Alice", "person", "Researcher", List.of(), Map.of()));
        assertThat(validator.attributeForwardCount).isZero();
    }

    private static KnowledgeExtractor extractorModel(ChatModel chatModel, Ontology ontology) {
        return new KnowledgeExtractor(
            chatModel,
            0,
            10_000,
            "English",
            List.of("Person", "Organization"),
            List.of("works at"),
            List.of(),
            false,
            100,
            40,
            null,
            true,
            ontology == null ? OntologyExtractionOverlay.empty() : OntologyExtractionOverlay.from(ontology),
            ontology == null ? null : OntologyAttributeSchema.from(ontology)
        );
    }

    private static KnowledgeExtractor extractor(
        ChatModel chatModel,
        Ontology ontology,
        KgExtractionValidator validator
    ) {
        return new KnowledgeExtractor(
            chatModel,
            0,
            10_000,
            "English",
            List.of("Person", "Organization"),
            List.of("works at"),
            List.of(),
            false,
            100,
            40,
            validator,
            true,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER),
            OntologyAttributeSchema.from(ontology)
        );
    }

    private static KnowledgeExtractor extractorWithGleaning(ChatModel chatModel, KgExtractionValidator validator) {
        var ontology = ontology();
        return new KnowledgeExtractor(
            chatModel,
            1,
            10_000,
            "English",
            List.of("Person", "Organization"),
            List.of("works at"),
            List.of(),
            false,
            100,
            40,
            validator,
            true,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER),
            OntologyAttributeSchema.from(ontology)
        );
    }

    private static Ontology ontology() {
        var person = new OntologyEntityType(
            "Person",
            "A person",
            List.of(
                new OntologyProperty("Age", OntologyPropertyType.INTEGER, "Age in years", null),
                new OntologyProperty("Birth Date", OntologyPropertyType.DATE, null, null),
                new OntologyProperty("Tags", OntologyPropertyType.LIST, null, null),
                new OntologyProperty("Nick Name", OntologyPropertyType.STRING, null, null)),
            null);
        var organization = new OntologyEntityType("Organization", "An organization", List.of(), null);
        var worksAt = new OntologyRelationType(
            "Works At",
            "Employment",
            List.of(new OntologyPattern("Person", "Organization")),
            List.of(new OntologyProperty("Since", OntologyPropertyType.DATE, null, null)),
            null);
        return new Ontology(List.of(person, organization), List.of(worksAt), null, Map.of());
    }

    private static Chunk chunk(String chunkId, String text) {
        return new Chunk(chunkId, "doc-1", text, text.length(), 0, Map.of());
    }

    private static int countOccurrences(String value, String needle) {
        var count = 0;
        var index = value.indexOf(needle);
        while (index >= 0) {
            count++;
            index = value.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static final class RecordingValidator implements KgExtractionValidator {
        private int attributeForwardCount;
        private List<UnmatchedLabel> forwarded = List.of();

        @Override
        public ExtractionResult validate(String chunkId, String chunkText, ExtractionResult extracted) {
            return extracted;
        }

        @Override
        public void onAttributeDiagnostics(List<UnmatchedLabel> diagnostics) {
            attributeForwardCount++;
            forwarded = new ArrayList<>(diagnostics);
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();

        private RecordingChatModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return responses.get(Math.min(requests.size() - 1, responses.size() - 1));
        }

        List<ChatRequest> requests() {
            return List.copyOf(requests);
        }
    }
}
