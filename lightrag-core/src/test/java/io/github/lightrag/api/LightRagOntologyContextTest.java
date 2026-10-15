package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.ontology.ExtractionMode;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyJsonCodec;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.OntologyTaskContext;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.StorageProvider;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagOntologyContextTest {

    private static final String WORKSPACE = "ontology-ctx-workspace";

    @Test
    void graphMutationsMarkOntologyDirty() {
        var provider = new RecordingGraphExtractionOptionsProvider();
        var rag = testRag(provider);

        rag.createEntity(WORKSPACE, CreateEntityRequest.builder().name("Alice").type("Person").build());
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        rag.createEntity(WORKSPACE, CreateEntityRequest.builder().name("Bob").type("Person").build());
        assertThat(provider.dirty).containsExactly(WORKSPACE, WORKSPACE);

        provider.dirty.clear();
        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .keywords("works with")
            .build());
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.editEntity(WORKSPACE, EditEntityRequest.builder()
            .entityName("Alice")
            .description("Researcher")
            .build());
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.updateRelation(WORKSPACE, UpdateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .description("Collaboration")
            .build());
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.deleteRelation(WORKSPACE, new DeleteRelationRequest("Alice", "Bob"));
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Alice"))
            .targetEntityName("Bob")
            .targetType("Person")
            .build());
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.createEntity(WORKSPACE, CreateEntityRequest.builder().name("Charlie").type("Person").build());
        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Bob")
            .targetEntityName("Charlie")
            .keywords("manages")
            .build());
        provider.dirty.clear();
        rag.deleteByRelation(WORKSPACE, "Bob", "Charlie");
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.deleteByEntity(WORKSPACE, "Charlie");
        assertThat(provider.dirty).containsExactly(WORKSPACE);
    }

    @Test
    void restoreImportAndDocumentDeletionMarkOntologyDirty(@TempDir Path tempDir) {
        var provider = new RecordingGraphExtractionOptionsProvider();
        var rag = testRag(provider);

        rag.ingest(WORKSPACE, List.of(new Document("doc-dirty", "Title", "content", Map.of())));
        provider.dirty.clear();

        var snapshotPath = tempDir.resolve("snapshot.json");
        rag.saveSnapshot(WORKSPACE, snapshotPath);
        provider.dirty.clear();
        rag.restoreSnapshot(WORKSPACE, snapshotPath);
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        byte[] exported = rag.exportData(WORKSPACE);
        rag.importData(WORKSPACE, exported);
        assertThat(provider.dirty).containsExactly(WORKSPACE);

        provider.dirty.clear();
        rag.deleteByDocumentId(WORKSPACE, "doc-dirty");
        assertThat(provider.dirty).containsExactly(WORKSPACE);
    }

    @Test
    void materializeDocumentGraphPropagatesTaskSubmitOntologyContext() {
        var chatModel = new RecordingChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-ctx", "Title", "content", Map.of())));

        var ontology = new Ontology(
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
        var overlay = OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT);
        var context = new OntologyTaskContext(GraphExtractionOptions.defaults(), ontology, overlay, false);
        var options = TaskSubmitOptions.builder().ontologyContext(context).build();

        rag.materializeDocumentGraph(WORKSPACE, "doc-ctx", GraphMaterializationMode.REBUILD, options);

        var materializationPrompts = chatModel.requests.stream()
            .filter(request -> request.systemPrompt().contains("STRICT ONTOLOGY MODE"))
            .toList();
        assertThat(materializationPrompts).isNotEmpty();
        assertThat(materializationPrompts.get(0).systemPrompt())
            .contains("Ontology Relationship Patterns:")
            .contains("Person -> Organization");
    }

    @Test
    void extractionPromptsReflectPreferAndOpenOverlayModes() {
        var chatModel = new RecordingChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        rag.ingest(WORKSPACE, List.of(
            new Document("doc-prefer", "Title", "content", Map.of()),
            new Document("doc-open", "Title", "content", Map.of())
        ));

        var ontology = new Ontology(
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

        rag.materializeDocumentGraph(
            WORKSPACE,
            "doc-prefer",
            GraphMaterializationMode.REBUILD,
            TaskSubmitOptions.builder()
                .ontologyContext(contextWithMode(ontology, ExtractionMode.PREFER, GraphExtractionOptions.defaults()))
                .build()
        );

        var preferPrompts = chatModel.requests.stream()
            .filter(request -> request.systemPrompt().contains("Preferred Relationship Patterns:"))
            .toList();
        assertThat(preferPrompts).isNotEmpty();
        assertThat(preferPrompts.get(0).systemPrompt())
            .contains("PREFER ONTOLOGY MODE")
            .contains("Person -> Organization")
            .doesNotContain("Reject any entity type")
            .doesNotContain("Allowed relationship keywords/types are");

        chatModel.requests.clear();
        var openOptions = GraphExtractionOptions.builder()
            .relationTypes(List.of("works at"))
            .build();
        rag.materializeDocumentGraph(
            WORKSPACE,
            "doc-open",
            GraphMaterializationMode.REBUILD,
            TaskSubmitOptions.builder()
                .ontologyContext(contextWithMode(ontology, ExtractionMode.OPEN, openOptions))
                .build()
        );

        var openPrompts = chatModel.requests.stream()
            .filter(request -> request.systemPrompt().contains("soft guidance"))
            .toList();
        assertThat(openPrompts).isNotEmpty();
        assertThat(openPrompts.get(0).systemPrompt())
            .doesNotContain("ONTOLOGY MODE")
            .doesNotContain("Preferred Relationship Patterns:")
            .doesNotContain("Ontology Relationship Patterns:");
    }

    private static OntologyTaskContext contextWithMode(
        Ontology ontology,
        ExtractionMode mode,
        GraphExtractionOptions options
    ) {
        var overlay = OntologyExtractionOverlay.from(ontology, mode);
        return new OntologyTaskContext(options, ontology, overlay, false);
    }

    @Test
    void resumeDocumentIngestRejectsCorruptStoredResolutionContext() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var now = Instant.now();
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-corrupt",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of("documentId", "doc-corrupt", "lightrag.ontology.resolutionContext", "not-json")
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-corrupt"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
    }

    @Test
    void resumeDocumentIngestRejectsBlankStoredResolutionContext() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var now = Instant.now();
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-blank",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of("documentId", "doc-blank", "lightrag.ontology.resolutionContext", "")
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-blank"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt")
            .hasMessageContaining("blank");
    }

    @Test
    void resumeDocumentIngestRejectsLooselyTypedStoredSnapshot() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        var context = new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, true);
        String corrupted = OntologyJsonCodec.writeContext(context)
            .replace("\"dropOnUnknownKeyword\":true", "\"dropOnUnknownKeyword\":{}");
        var now = Instant.now();
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-loose",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of("documentId", "doc-loose", "lightrag.ontology.resolutionContext", corrupted)
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-loose"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
        assertThatThrownBy(() -> rag.submitResumeDocumentIngest(WORKSPACE, "doc-loose"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");

        String truncatedVersion = OntologyJsonCodec.writeContext(context)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297");
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-loose-version",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-loose-version",
                "lightrag.ontology.resolutionContext", truncatedVersion
            )
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-loose-version"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
        assertThatThrownBy(() -> rag.submitResumeDocumentIngest(WORKSPACE, "doc-loose-version"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");

        String coercedBoolean = OntologyJsonCodec.writeContext(context)
            .replaceAll("\"enabled\":(true|false)", "\"enabled\":0");
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-loose-enabled",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-loose-enabled",
                "lightrag.ontology.resolutionContext", coercedBoolean
            )
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-loose-enabled"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
        assertThatThrownBy(() -> rag.submitResumeDocumentIngest(WORKSPACE, "doc-loose-enabled"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");

        String emptyStringEnabled = OntologyJsonCodec.writeContext(context)
            .replaceAll("\"enabled\":(true|false)", "\"enabled\":\"\"");
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-loose-empty",
            WORKSPACE,
            TaskType.RESUME_DOCUMENT_INGEST,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-loose-empty",
                "lightrag.ontology.resolutionContext", emptyStringEnabled
            )
        ));

        assertThatThrownBy(() -> rag.resumeDocumentIngest(WORKSPACE, "doc-loose-empty"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
        assertThatThrownBy(() -> rag.submitResumeDocumentIngest(WORKSPACE, "doc-loose-empty"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("is corrupt");
    }

    @Test
    void resumeWorksWhenTaskDocumentStoreIsUnsupported() throws InterruptedException {
        var storage = InMemoryStorageProvider.create();
        var provider = (AtomicStorageProvider) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {AtomicStorageProvider.class},
            (proxy, method, args) -> {
                if ("taskDocumentStore".equals(method.getName())) {
                    throw new UnsupportedOperationException(StorageProvider.TASK_DOCUMENT_STORE_UNSUPPORTED_MESSAGE);
                }
                try {
                    return method.invoke(storage, args);
                } catch (java.lang.reflect.InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
        );
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(provider)
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-optional-store", "Title", "content", Map.of())));

        var context = new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, false);
        var now = Instant.now();
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-optional-store",
            WORKSPACE,
            TaskType.INGEST_DOCUMENTS,
            TaskStatus.SUCCEEDED,
            now,
            now,
            now,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-optional-store",
                "lightrag.ontology.resolutionContext", OntologyJsonCodec.writeContext(context)
            )
        ));

        var firstTaskId = rag.submitResumeDocumentIngest(WORKSPACE, "doc-optional-store");
        assertThat(awaitTerminalTask(rag, firstTaskId).status()).isEqualTo(TaskStatus.SUCCEEDED);

        var later = now.plusSeconds(60);
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-optional-store-unrelated",
            WORKSPACE,
            TaskType.INGEST_DOCUMENTS,
            TaskStatus.SUCCEEDED,
            later,
            later,
            later,
            "",
            null,
            false,
            Map.of()
        ));

        var secondTaskId = rag.submitResumeDocumentIngest(WORKSPACE, "doc-optional-store");
        var secondTask = awaitTerminalTask(rag, secondTaskId);
        assertThat(secondTask.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(secondTask.metadata())
            .containsEntry("ontologyContextFingerprint", context.fingerprint());

        rag.resumeDocumentIngest(WORKSPACE, "doc-optional-store");
    }

    @Test
    void syncIngestResolvesWorkspaceOntologyContextOnce() {
        var provider = new RecordingGraphExtractionOptionsProvider();
        var rag = testRag(provider);

        rag.ingest(WORKSPACE, List.of(new Document("doc-once", "Title", "content", Map.of())));

        assertThat(provider.resolveCalls).hasValue(1);
        assertThat(provider.overlayCalls).hasValue(1);
    }

    @Test
    void syncIngestRejectsStrictOntologyOverlayWithoutBoundOntology() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
        var provider = new RecordingGraphExtractionOptionsProvider() {
            @Override
            public Optional<OntologyExtractionOverlay> ontologyExtractionOverlay(WorkspaceScope scope) {
                return Optional.of(OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT));
            }
        };
        var rag = testRag(provider);

        assertThatThrownBy(() -> rag.ingest(
            WORKSPACE,
            List.of(new Document("doc-strict-unbound", "Title", "content", Map.of()))
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ontology is bound for workspace " + WORKSPACE);
    }

    @Test
    void materializeDocumentGraphEnforcesStrictOntologyValidator() {
        var storage = InMemoryStorageProvider.create();
        var chatModel = new StrictOntologyChatModel();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-validate", "Title", "content", Map.of())));

        var ontology = ontology();
        var overlay = OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT);
        var context = new OntologyTaskContext(GraphExtractionOptions.defaults(), ontology, overlay, true);
        rag.materializeDocumentGraph(
            WORKSPACE,
            "doc-validate",
            GraphMaterializationMode.REBUILD,
            TaskSubmitOptions.builder().ontologyContext(context).build()
        );

        assertThat(chatModel.sawStrictOntologyPrompt()).isTrue();
        assertThat(storage.graphStore().loadEntity("alice")).isPresent();
        assertThat(storage.graphStore().loadEntity("zorg")).isEmpty();
    }

    @Test
    void resumeDocumentIngestPrefersLatestAssociatedTaskContext() throws InterruptedException {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-history", "Title", "content", Map.of())));

        var legacyContext = new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, false);
        var strictContext = new OntologyTaskContext(
            GraphExtractionOptions.defaults(),
            ontology(),
            OntologyExtractionOverlay.from(ontology(), ExtractionMode.STRICT),
            true
        );

        var base = Instant.now().minusSeconds(3600);
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-history-early",
            WORKSPACE,
            TaskType.INGEST_DOCUMENTS,
            TaskStatus.SUCCEEDED,
            base,
            base,
            base,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-history",
                "lightrag.ontology.resolutionContext", OntologyJsonCodec.writeContext(strictContext)
            )
        ));
        var later = base.plusSeconds(600);
        storage.taskStore().save(new TaskStore.TaskRecord(
            "task-history-late",
            WORKSPACE,
            TaskType.INGEST_DOCUMENTS,
            TaskStatus.SUCCEEDED,
            later,
            later,
            later,
            "",
            null,
            false,
            Map.of(
                "documentId", "doc-history",
                "lightrag.ontology.resolutionContext", OntologyJsonCodec.writeContext(legacyContext)
            )
        ));

        var resolvedTaskId = rag.submitResumeDocumentIngest(WORKSPACE, "doc-history");
        var resolvedTask = awaitTerminalTask(rag, resolvedTaskId);
        assertThat(resolvedTask.metadata())
            .containsEntry("ontologyContextFingerprint", legacyContext.fingerprint());

        var explicitTaskId = rag.submitResumeDocumentIngest(
            WORKSPACE,
            "doc-history",
            TaskSubmitOptions.builder().ontologyContext(strictContext).build()
        );
        var explicitTask = awaitTerminalTask(rag, explicitTaskId);
        assertThat(explicitTask.metadata())
            .containsEntry("ontologyContextFingerprint", strictContext.fingerprint());
    }

    @Test
    void asyncDocumentDeleteMarksOntologyDirty() throws InterruptedException {
        var provider = new RecordingGraphExtractionOptionsProvider();
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .graphExtractionOptionsProvider(provider)
            .build();

        rag.ingest(WORKSPACE, List.of(new Document("doc-async-dirty", "Title", "content", Map.of())));
        provider.dirty.clear();

        var taskId = rag.submitDeleteByDocumentId(WORKSPACE, "doc-async-dirty");
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(provider.dirty).containsExactly(WORKSPACE);
    }

    @Test
    void graphManagementAndDeletionPathsResolveWorkspaceOntologyContext() {
        var provider = new RecordingGraphExtractionOptionsProvider();
        var rag = testRag(provider);

        rag.createEntity(WORKSPACE, CreateEntityRequest.builder().name("Alice").type("Person").build());
        assertThat(provider.resolveCalls).hasValue(1);
        assertThat(provider.overlayCalls).hasValue(1);

        provider.resolveCalls.set(0);
        provider.overlayCalls.set(0);
        rag.deleteByEntity(WORKSPACE, "Alice");
        assertThat(provider.resolveCalls).hasValue(1);
        assertThat(provider.overlayCalls).hasValue(1);

        rag.ingest(WORKSPACE, List.of(new Document("doc-delete-scope", "Title", "content", Map.of())));
        provider.resolveCalls.set(0);
        provider.overlayCalls.set(0);
        rag.deleteByDocumentId(WORKSPACE, "doc-delete-scope");
        assertThat(provider.resolveCalls).hasValue(1);
        assertThat(provider.overlayCalls).hasValue(1);
    }

    @Test
    void materializeDocumentGraphRejectsStrictOntologyOverlayWithoutBoundOntology() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
        var provider = new RecordingGraphExtractionOptionsProvider() {
            @Override
            public Optional<OntologyExtractionOverlay> ontologyExtractionOverlay(WorkspaceScope scope) {
                return Optional.of(OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT));
            }
        };
        var rag = testRag(provider);

        assertThatThrownBy(() -> rag.materializeDocumentGraph(
            WORKSPACE,
            "doc-strict-unbound",
            GraphMaterializationMode.REBUILD
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ontology is bound for workspace " + WORKSPACE);
    }

    private static Ontology ontology() {
        return new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
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

    private static LightRag testRag(RecordingGraphExtractionOptionsProvider provider) {
        return LightRag.builder()
            .chatModel(new NoopChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .graphExtractionOptionsProvider(provider)
            .build();
    }

    private static class RecordingGraphExtractionOptionsProvider implements GraphExtractionOptionsProvider {
        final List<String> dirty = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger resolveCalls = new AtomicInteger();
        final AtomicInteger overlayCalls = new AtomicInteger();

        @Override
        public Optional<GraphExtractionOptions> resolve(WorkspaceScope scope) {
            resolveCalls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public Optional<OntologyExtractionOverlay> ontologyExtractionOverlay(WorkspaceScope scope) {
            overlayCalls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public void markOntologyDirty(WorkspaceScope scope) {
            dirty.add(scope.workspaceId());
        }
    }

    private static final class StrictOntologyChatModel implements ChatModel {
        private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            if (request.systemPrompt().contains("STRICT ONTOLOGY MODE")) {
                return """
                    {
                      "entities": [
                        {
                          "name": "Alice",
                          "type": "Person",
                          "description": "Researcher",
                          "aliases": []
                        },
                        {
                          "name": "Zorg",
                          "type": "Alien",
                          "description": "Visitor from the planet Zx-991",
                          "aliases": []
                        }
                      ],
                      "relations": []
                    }
                    """;
            }
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }

        boolean sawStrictOntologyPrompt() {
            return requests.stream().anyMatch(request -> request.systemPrompt().contains("STRICT ONTOLOGY MODE"));
        }
    }

    private static final class NoopChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class RecordingChatModel implements ChatModel {
        private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String generate(ChatRequest request) {
            requests.add(request);
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of((double) text.length())).toList();
        }
    }
}
