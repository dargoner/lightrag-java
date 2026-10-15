package io.github.lightrag.ontology.discovery;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.ChatModel.ChatRequest;
import io.github.lightrag.types.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyRelationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyDiscoveryTest {

    @Test
    void emptyChunkListReturnsEmptyDraft() {
        var chatModel = new StubChatModel("{}");
        var request = new DiscoveryRequest(chatModel, List.of(), DiscoveryRequest.DiscoveryOptions.defaults());

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).isEmpty();
        assertThat(result.draft().relationTypes()).isEmpty();
    }

    @Test
    void mergesProposalsFromMultipleChunks() {
        var proposal1 = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var proposal2 = """
            {
              "entityTypes": [
                {"label": "Organization", "description": "An organization", "properties": []}
              ],
              "relationTypes": [
                {
                  "label": "works at",
                  "description": "employment",
                  "patterns": [{"sourceLabel": "Person", "targetLabel": "Organization"}],
                  "properties": []
                }
              ]
            }
            """;
        var chatModel = new StubChatModel(List.of(proposal1, proposal2));
        var chunks = List.of(
            new Chunk("c1", "doc1", "Alice works at Acme.", 5, 0, Map.of()),
            new Chunk("c2", "doc1", "Bob works at Beta.", 5, 1, Map.of())
        );
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 2, 0, 0L)
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).hasSize(2);
        assertThat(result.draft().relationTypes()).hasSize(1);
        assertThat(result.draft().relationTypes().get(0).patterns()).hasSize(1);
    }

    @Test
    void randomSamplingIsReproducibleWithSameSeed() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = List.of(
            new Chunk("c1", "doc1", "one", 1, 0, Map.of()),
            new Chunk("c2", "doc1", "two", 1, 1, Map.of()),
            new Chunk("c3", "doc1", "three", 1, 2, Map.of()),
            new Chunk("c4", "doc1", "four", 1, 3, Map.of())
        );
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 2, 0, 0L)
        );

        var discovery = new OntologyDiscovery();
        var resultA = discovery.execute(request, DiscoverySampleStrategy.random(42L));
        var resultB = discovery.execute(request, DiscoverySampleStrategy.random(42L));

        assertThat(resultA.draft().entityTypes()).hasSize(1);
        assertThat(resultB.draft().entityTypes()).hasSize(1);
        assertThat(chatModel.requests).hasSize(4);
    }

    @Test
    void skipsUnparseableProposalsAndKeepsValidOnes() {
        var validProposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(List.of("not json", validProposal));
        var chunks = List.of(
            new Chunk("c1", "doc1", "garbage", 1, 0, Map.of()),
            new Chunk("c2", "doc1", "Alice is a person.", 5, 1, Map.of())
        );
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 2, 0, 0L)
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).hasSize(1);
        assertThat(result.draft().entityTypes().get(0).label()).isEqualTo("Person");
    }

    @Test
    void extractsJsonFromCodeFence() {
        var proposal = """
            ```json
            {
              "entityTypes": [
                {"label": "City", "description": "A city", "properties": []}
              ],
              "relationTypes": []
            }
            ```
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = List.of(new Chunk("c1", "doc1", "Paris is a city.", 5, 0, Map.of()));
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 1, 0, 0L)
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).hasSize(1);
        assertThat(result.draft().entityTypes().get(0).label()).isEqualTo("City");
    }

    @Test
    void draftIsNotPersistedAndHasNoVersion() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice.", 2, 0, Map.of())),
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().version()).isNull();
    }

    @Test
    void throwsWhenNoProposalIsParseable() {
        var chatModel = new StubChatModel("not json");
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "text", 1, 0, Map.of())),
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        assertThatThrownBy(() -> new OntologyDiscovery().execute(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no parseable proposals");
    }

    @Test
    void reportsConflictsWithBaseOntology() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": [
                  {"name": "age", "type": "string", "description": "years"}
                ]}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var baseOntology = new io.github.lightrag.ontology.Ontology(
            List.of(new io.github.lightrag.ontology.OntologyEntityType(
                "Person",
                "A person",
                List.of(new io.github.lightrag.ontology.OntologyProperty(
                    "age", io.github.lightrag.ontology.OntologyPropertyType.INTEGER, "years", null
                )),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice is 30.", 3, 0, Map.of())),
            baseOntology,
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.conflicts()).isNotEmpty();
        assertThat(result.fingerprint()).isNotBlank();
    }

    @Test
    void swappedChunkOrderStillReportsBaselineConflicts() {
        var integerProposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": [
                  {"name": "age", "type": "integer", "description": "years"}
                ]}
              ],
              "relationTypes": []
            }
            """;
        var stringProposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": [
                  {"name": "age", "type": "string", "description": "years"}
                ]}
              ],
              "relationTypes": []
            }
            """;
        var baseOntology = new io.github.lightrag.ontology.Ontology(
            List.of(new io.github.lightrag.ontology.OntologyEntityType(
                "Person",
                "A person",
                List.of(new io.github.lightrag.ontology.OntologyProperty(
                    "age", io.github.lightrag.ontology.OntologyPropertyType.INTEGER, "years", null
                )),
                null
            )),
            List.of(),
            null,
            Map.of()
        );
        DiscoverySampleStrategy inOrder = (population, sampleSize) -> List.copyOf(population);
        var chunk1 = new Chunk("c1", "doc1", "Alice is 30.", 3, 0, Map.of());
        var chunk2 = new Chunk("c2", "doc1", "Alice is thirty.", 3, 1, Map.of());
        var options = new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 2, 0, 0L);

        var forward = new OntologyDiscovery().execute(
            new DiscoveryRequest(
                new StubChatModel(List.of(integerProposal, stringProposal)),
                List.of(chunk1, chunk2),
                baseOntology,
                options
            ),
            inOrder
        );
        var swapped = new OntologyDiscovery().execute(
            new DiscoveryRequest(
                new StubChatModel(List.of(stringProposal, integerProposal)),
                List.of(chunk2, chunk1),
                baseOntology,
                options
            ),
            inOrder
        );

        assertThat(forward.conflicts()).containsExactly(
            "entity 'person' property 'age' changed type from INTEGER to STRING"
        );
        assertThat(swapped.conflicts()).isEqualTo(forward.conflicts());
        assertThat(forward.draft().entityTypes()).isEmpty();
        assertThat(swapped.draft().entityTypes()).isEmpty();
    }

    @Test
    void retriesFailedProposals() {
        var validProposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(List.of("not json", validProposal));
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice.", 2, 0, Map.of())),
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 1, 1, 0L)
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).hasSize(1);
        assertThat(chatModel.requests).hasSize(2);
        assertThat(chatModel.requests.get(1).userPrompt()).contains("could not be parsed");
        assertThat(chatModel.requests.get(1).conversationHistory()).hasSize(2);
    }

    @Test
    void interruptDuringLastChunkResponseStillThrowsCancellationException() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        ChatModel interruptingChatModel = request -> {
            Thread.currentThread().interrupt();
            return proposal;
        };
        var request = new DiscoveryRequest(
            interruptingChatModel,
            List.of(new Chunk("c1", "doc1", "Alice.", 2, 0, Map.of())),
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        try {
            assertThatThrownBy(() -> new OntologyDiscovery().execute(request))
                .isInstanceOf(java.util.concurrent.CancellationException.class)
                .hasMessageContaining("cancelled");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stratifiedByDocumentSamplesAcrossDocuments() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = new java.util.ArrayList<Chunk>();
        for (int doc = 0; doc < 3; doc++) {
            for (int chunk = 0; chunk < 5; chunk++) {
                chunks.add(new Chunk("c" + doc + "_" + chunk, "doc" + doc, "doc" + doc + " chunk " + chunk, 1, chunk, Map.of()));
            }
        }
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 6, 0, 0L)
        );

        new OntologyDiscovery().execute(request, DiscoverySampleStrategy.stratifiedByDocument(42L));

        var sampledDocumentIds = chatModel.requests.stream()
            .map(r -> r.userPrompt())
            .map(content -> chunks.stream().filter(c -> content.contains(c.text())).findFirst())
            .flatMap(Optional::stream)
            .map(Chunk::documentId)
            .distinct()
            .toList();
        assertThat(sampledDocumentIds).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void baselineSubsetProposalProducesEmptyDraftWithoutConflicts() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var baseOntology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
        var chatModel = new StubChatModel(proposal);
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice.", 2, 0, Map.of())),
            baseOntology,
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().entityTypes()).isEmpty();
        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.failures()).isEmpty();
        assertThat(result.fingerprint()).isNotBlank();
    }

    @Test
    void relationPatternAdditionBeyondBaselineIsReportedAsConflict() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []},
                {"label": "Organization", "description": "An organization", "properties": []}
              ],
              "relationTypes": [
                {
                  "label": "works at",
                  "description": "employment",
                  "patterns": [
                    {"sourceLabel": "Person", "targetLabel": "Organization"},
                    {"sourceLabel": "Organization", "targetLabel": "Organization"}
                  ],
                  "properties": []
                }
              ]
            }
            """;
        var baseOntology = new Ontology(
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
        var chatModel = new StubChatModel(proposal);
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice works at Acme.", 5, 0, Map.of())),
            baseOntology,
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        var result = new OntologyDiscovery().execute(request);

        assertThat(result.draft().relationTypes()).isEmpty();
        assertThat(result.conflicts())
            .anyMatch(conflict -> conflict.contains("Relation 'works at' adds properties or patterns beyond the baseline"));
    }

    @Test
    void stratifiedByDocumentGuaranteesCoverageAndDistributesSlotsProportionally() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = new java.util.ArrayList<Chunk>();
        chunks.add(new Chunk("d0_0", "doc0", "doc0 chunk 0", 1, 0, Map.of()));
        chunks.add(new Chunk("d0_1", "doc0", "doc0 chunk 1", 1, 1, Map.of()));
        for (int doc = 1; doc <= 2; doc++) {
            for (int chunk = 0; chunk < 10; chunk++) {
                chunks.add(new Chunk("d" + doc + "_" + chunk, "doc" + doc, "doc" + doc + " chunk " + chunk, 1, chunk, Map.of()));
            }
        }
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 6, 0, 0L)
        );

        new OntologyDiscovery().execute(request, DiscoverySampleStrategy.stratifiedByDocument(7L));

        var sampledDocumentIds = chatModel.requests.stream()
            .map(r -> r.userPrompt())
            .map(content -> chunks.stream().filter(c -> content.contains(c.text())).findFirst())
            .flatMap(Optional::stream)
            .map(Chunk::documentId)
            .toList();
        var counts = sampledDocumentIds.stream()
            .collect(Collectors.groupingBy(docId -> docId, Collectors.counting()));
        assertThat(counts.keySet()).containsExactlyInAnyOrder("doc0", "doc1", "doc2");
        assertThat(counts.values()).allMatch(count -> count >= 1);
        assertThat(sampledDocumentIds).hasSize(6);
        assertThat(counts.get("doc1") + counts.get("doc2")).isGreaterThan(counts.get("doc0"));
    }

    @Test
    void stratifiedSamplingRedistributesSlotsWhenSmallDocumentIsExhausted() {
        var chunks = new java.util.ArrayList<Chunk>();
        chunks.add(new Chunk("a0", "docA", "docA chunk 0", 1, 0, Map.of()));
        for (int chunk = 0; chunk < 20; chunk++) {
            chunks.add(new Chunk("b" + chunk, "docB", "docB chunk " + chunk, 1, chunk, Map.of()));
        }

        var sample = DiscoverySampleStrategy.stratifiedByDocument(11L).select(chunks, 10);

        assertThat(sample).hasSize(10);
        var counts = sample.stream()
            .collect(Collectors.groupingBy(Chunk::documentId, Collectors.counting()));
        assertThat(counts.get("docA")).isEqualTo(1L);
        assertThat(counts.get("docB")).isEqualTo(9L);
    }

    @Test
    void interruptedThreadThrowsCancellationException() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "Alice.", 2, 0, Map.of())),
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new OntologyDiscovery().execute(request))
                .isInstanceOf(java.util.concurrent.CancellationException.class)
                .hasMessageContaining("cancelled");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void fingerprintReflectsSampleSelectionNotDraftContent() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = List.of(
            new Chunk("c1", "doc1", "one", 1, 0, Map.of()),
            new Chunk("c2", "doc1", "two", 1, 1, Map.of())
        );
        var request = new DiscoveryRequest(
            chatModel,
            chunks,
            new DiscoveryRequest.DiscoveryOptions(10, 10, 5, 1, 0, 0L)
        );

        DiscoverySampleStrategy firstChunk = (population, sampleSize) -> List.of(population.get(0));
        DiscoverySampleStrategy secondChunk = (population, sampleSize) -> List.of(population.get(1));

        var resultA = new OntologyDiscovery().execute(request, firstChunk);
        var resultB = new OntologyDiscovery().execute(request, secondChunk);

        assertThat(resultA.draft().entityTypes()).hasSize(1);
        assertThat(resultB.draft().entityTypes()).hasSize(1);
        assertThat(resultA.fingerprint()).isNotBlank();
        assertThat(resultB.fingerprint()).isNotBlank();
        assertThat(resultA.fingerprint()).isNotEqualTo(resultB.fingerprint());
    }

    @Test
    void sampleFingerprintUsesStrategyIdNotRuntimeClassName() {
        var proposal = """
            {
              "entityTypes": [
                {"label": "Person", "description": "A person", "properties": []}
              ],
              "relationTypes": []
            }
            """;
        var chatModel = new StubChatModel(proposal);
        var chunks = List.of(new Chunk("c1", "doc1", "one", 1, 0, Map.of()));
        var request = new DiscoveryRequest(chatModel, chunks, DiscoveryRequest.DiscoveryOptions.defaults());

        DiscoverySampleStrategy firstA = new DiscoverySampleStrategy() {
            @Override
            public List<Chunk> select(List<Chunk> population, int sampleSize) {
                return List.of(population.get(0));
            }

            @Override
            public String strategyId() {
                return "testSample/v1";
            }
        };
        DiscoverySampleStrategy firstB = new DiscoverySampleStrategy() {
            @Override
            public List<Chunk> select(List<Chunk> population, int sampleSize) {
                return List.of(population.get(0));
            }

            @Override
            public String strategyId() {
                return "testSample/v1";
            }
        };

        assertThat(firstA.getClass().getName()).isNotEqualTo(firstB.getClass().getName());
        var resultA = new OntologyDiscovery().execute(request, firstA);
        var resultB = new OntologyDiscovery().execute(request, firstB);

        assertThat(resultA.fingerprint()).isEqualTo(resultB.fingerprint());
        assertThat(DiscoverySampleStrategy.random(1L).strategyId()).isEqualTo("random/v1");
        assertThat(DiscoverySampleStrategy.firstN().strategyId()).isEqualTo("firstN/v1");
        assertThat(DiscoverySampleStrategy.stratifiedByDocument(1L).strategyId())
            .isEqualTo("stratifiedByDocument/v1");
        DiscoverySampleStrategy lambdaStrategy = (population, sampleSize) -> List.of(population.get(0));
        assertThat(lambdaStrategy.strategyId()).isEqualTo("custom");
    }

    @Test
    void proposalWithNoEntityOrRelationTypesIsTreatedAsUnparseable() {
        var chatModel = new StubChatModel("{\"entityTypes\":[],\"relationTypes\":[]}");
        var request = new DiscoveryRequest(
            chatModel,
            List.of(new Chunk("c1", "doc1", "text", 1, 0, Map.of())),
            DiscoveryRequest.DiscoveryOptions.defaults()
        );

        assertThatThrownBy(() -> new OntologyDiscovery().execute(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no parseable proposals");
    }

    private static final class StubChatModel implements ChatModel {
        private final List<String> responses;
        private final List<ChatRequest> requests = new java.util.ArrayList<>();
        private int index = 0;

        StubChatModel(String response) {
            this.responses = List.of(response);
        }

        StubChatModel(List<String> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            var response = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return response;
        }
    }
}
