package io.github.lightrag.api;

import io.github.lightrag.indexing.Chunker;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyTaskContext;
import io.github.lightrag.ontology.UnmatchedLabel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagOntologyUnmatchedCallbackTest {

    private static final String WORKSPACE = "ontology-unmatched-workspace";

    private static final String EMPTY_PAYLOAD = """
        {"entities": [], "relations": []}
        """;

    /** Alice conforms, Zorg does not, Blob falls back to the universal {@code Other} type. */
    private static final String MIXED_CONFORMANCE_PAYLOAD = """
        {
          "entities": [
            {"name": "Alice", "type": "Alpha", "description": "Researcher", "aliases": []},
            {"name": "Zorg", "type": "Beta", "description": "Visitor", "aliases": []},
            {"name": "Blob", "type": "Other", "description": "Unclassified", "aliases": []}
          ],
          "relations": []
        }
        """;

    private static final String TWO_CONFORMING_ENTITIES_PAYLOAD = """
        {
          "entities": [
            {"name": "Alice", "type": "Alpha", "description": "Researcher", "aliases": []},
            {"name": "Bob", "type": "Alpha", "description": "Engineer", "aliases": []}
          ],
          "relations": []
        }
        """;

    /** Refinement window patch that re-introduces a non-conforming entity. */
    private static final String WINDOW_NON_CONFORMING_ENTITY_PAYLOAD = """
        {
          "entities": [
            {
              "name": "Zorg",
              "type": "Beta",
              "description": "Visitor",
              "aliases": [],
              "supportingChunkIndexes": [0]
            }
          ],
          "relations": [],
          "warnings": []
        }
        """;

    @Test
    void reportsUnmatchedEntityWithSubmittedContextFromSubmittedIngestTask() throws InterruptedException {
        var provider = new RecordingProvider();
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new PreferOntologyChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .chunker(new SingleChunkChunker())
            .entityExtractMaxGleaning(0)
            .graphExtractionOptionsProvider(provider)
            .build();

        var ontology = entityTypeOntology("Alpha");
        var context = new OntologyTaskContext(
            GraphExtractionOptions.defaults(),
            ontology,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER),
            false
        );

        var taskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-unmatched", "Title", "Alice and Zorg collaborate", Map.of())),
            TaskSubmitOptions.builder().ontologyContext(context).build()
        );
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(provider.unmatched).hasSize(1);
        var call = provider.unmatched.get(0);
        assertThat(call.scope()).isEqualTo(new WorkspaceScope(WORKSPACE));
        assertThat(call.context()).isSameAs(context);
        assertThat(call.context().ontology()).isEqualTo(ontology);
        assertThat(call.label()).isEqualTo(new UnmatchedLabel(
            UnmatchedLabel.Kind.ENTITY,
            "Beta",
            "doc-unmatched:0",
            UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE
        ));
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("blob")).isPresent();
        assertThat(storage.graphStore().loadEntity("zorg")).isEmpty();
    }

    @Test
    void configuredValidatorStillAppliesAndCallbackStaysSilentWithoutOntology() throws InterruptedException {
        var provider = new RecordingProvider();
        var configValidatorCalls = new AtomicInteger();
        var rag = LightRag.builder()
            .chatModel(new PreferOntologyChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .chunker(new SingleChunkChunker())
            .entityExtractMaxGleaning(0)
            .graphExtractionOptionsProvider(provider)
            .kgExtractionValidator((chunkId, chunkText, extracted) -> {
                configValidatorCalls.incrementAndGet();
                return extracted;
            })
            .build();

        var noContextTaskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-no-context", "Title", "Alice and Zorg collaborate", Map.of()))
        );
        assertThat(awaitTerminalTask(rag, noContextTaskId).status()).isEqualTo(TaskStatus.SUCCEEDED);

        var emptyOntologyContext = new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, false);
        var emptyContextTaskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-empty-context", "Title", "Alice and Zorg collaborate", Map.of())),
            TaskSubmitOptions.builder().ontologyContext(emptyOntologyContext).build()
        );
        assertThat(awaitTerminalTask(rag, emptyContextTaskId).status()).isEqualTo(TaskStatus.SUCCEEDED);

        // Two chunks total; each chunk is validated once during primary extraction and once
        // by the post-refinement final validation pass.
        assertThat(configValidatorCalls).hasValue(4);
        assertThat(provider.unmatched).isEmpty();
    }

    @Test
    void reportsUnmatchedEntityOnGraphRebuildPath() {
        var provider = new RecordingProvider();
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new StrictOntologyChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .chunker(new SingleChunkChunker())
            .entityExtractMaxGleaning(0)
            .graphExtractionOptionsProvider(provider)
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-rebuild", "Title", "Alice and Zorg collaborate", Map.of())));
        assertThat(provider.unmatched).isEmpty();

        var ontology = entityTypeOntology("Alpha");
        var context = new OntologyTaskContext(
            GraphExtractionOptions.defaults(),
            ontology,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT),
            false
        );

        var result = rag.materializeDocumentGraph(
            WORKSPACE,
            "doc-rebuild",
            GraphMaterializationMode.REBUILD,
            TaskSubmitOptions.builder().ontologyContext(context).build()
        );

        assertThat(result.finalStatus()).isEqualTo(GraphMaterializationStatus.MERGED);
        assertThat(provider.unmatched).hasSize(1);
        var call = provider.unmatched.get(0);
        assertThat(call.scope()).isEqualTo(new WorkspaceScope(WORKSPACE));
        assertThat(call.context()).isSameAs(context);
        assertThat(call.label()).isEqualTo(new UnmatchedLabel(
            UnmatchedLabel.Kind.ENTITY,
            "Beta",
            "doc-rebuild:0",
            UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE
        ));
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("zorg")).isEmpty();
    }

    @Test
    void refinementFinalValidationReportsReintroducedUnmatchedEntity() throws InterruptedException {
        var provider = new RecordingProvider();
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new RefinementChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .chunker(new SingleChunkChunker())
            .entityExtractMaxGleaning(0)
            .chunkExtractParallelism(1)
            .contextualExtractionRefinement(true)
            .graphExtractionOptionsProvider(provider)
            .build();

        var ontology = entityTypeOntology("Alpha");
        var context = new OntologyTaskContext(
            GraphExtractionOptions.defaults(),
            ontology,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER),
            false
        );

        var taskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-refine", "Title", "Alice与Bob的合作依赖", Map.of())),
            TaskSubmitOptions.builder().ontologyContext(context).build()
        );
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(provider.unmatched).hasSize(1);
        var call = provider.unmatched.get(0);
        assertThat(call.context()).isSameAs(context);
        assertThat(call.label()).isEqualTo(new UnmatchedLabel(
            UnmatchedLabel.Kind.ENTITY,
            "Beta",
            "doc-refine:0",
            UnmatchedLabel.Reason.UNKNOWN_ENTITY_TYPE
        ));
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("bob")).isPresent();
        assertThat(storage.graphStore().loadEntity("zorg")).isEmpty();
    }

    @Test
    void lambdaOnlyProviderStillRunsOntologyValidationEndToEnd() throws InterruptedException {
        GraphExtractionOptionsProvider lambdaOnlyProvider = scope -> Optional.empty();
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new PreferOntologyChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .chunker(new SingleChunkChunker())
            .entityExtractMaxGleaning(0)
            .graphExtractionOptionsProvider(lambdaOnlyProvider)
            .build();

        var ontology = entityTypeOntology("Alpha");
        var context = new OntologyTaskContext(
            GraphExtractionOptions.defaults(),
            ontology,
            OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER),
            false
        );

        var taskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-lambda", "Title", "Alice and Zorg collaborate", Map.of())),
            TaskSubmitOptions.builder().ontologyContext(context).build()
        );
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("zorg")).isEmpty();
    }

    private static Ontology entityTypeOntology(String label) {
        return new Ontology(
            List.of(new OntologyEntityType(label, "An " + label + " entity", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
    }

    private static TaskSnapshot awaitTerminalTask(LightRag rag, String taskId) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            var task = rag.getTask(WORKSPACE, taskId);
            if (task.status().isTerminal()) {
                return task;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("task did not finish: " + taskId);
    }

    private static final class RecordingProvider implements GraphExtractionOptionsProvider {
        private final List<UnmatchedCall> unmatched = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Optional<GraphExtractionOptions> resolve(WorkspaceScope scope) {
            return Optional.empty();
        }

        @Override
        public void onOntologyUnmatched(WorkspaceScope scope, OntologyTaskContext context, UnmatchedLabel label) {
            unmatched.add(new UnmatchedCall(scope, context, label));
        }
    }

    private record UnmatchedCall(WorkspaceScope scope, OntologyTaskContext context, UnmatchedLabel label) {
    }

    private static final class PreferOntologyChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("PREFER ONTOLOGY MODE")) {
                return MIXED_CONFORMANCE_PAYLOAD;
            }
            return EMPTY_PAYLOAD;
        }
    }

    private static final class StrictOntologyChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("STRICT ONTOLOGY MODE")) {
                return MIXED_CONFORMANCE_PAYLOAD;
            }
            return EMPTY_PAYLOAD;
        }
    }

    private static final class RefinementChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            if (request.systemPrompt().contains("supportingChunkIndexes")) {
                return WINDOW_NON_CONFORMING_ENTITY_PAYLOAD;
            }
            if (request.systemPrompt().contains("PREFER ONTOLOGY MODE")) {
                return TWO_CONFORMING_ENTITIES_PAYLOAD;
            }
            return EMPTY_PAYLOAD;
        }
    }

    private static final class SingleChunkChunker implements Chunker {
        @Override
        public List<Chunk> chunk(Document document) {
            var content = document.content();
            return List.of(new Chunk(
                document.id() + ":0",
                document.id(),
                content,
                content.length(),
                0,
                Map.of()
            ));
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of((double) text.length())).toList();
        }
    }
}
