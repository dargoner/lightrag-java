package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.PreChunkedDocument;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.lightrag.support.RelationIds.relationId;

class LightRagTaskApiTest {
    private static final String WORKSPACE = "default";

    @Test
    void ingestPreChunkedPersistsProvidedChunkIdsWithoutRechunking() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();

        rag.ingestPreChunked(WORKSPACE, List.of(new PreChunkedDocument(
            "doc-1",
            "Title",
            List.of(
                new Chunk("chunk-1", "doc-1", "Alice works with Bob", 4, 0, Map.of("source", "prechunked-test")),
                new Chunk("chunk-2", "doc-1", "Bob supports Carol", 4, 1, Map.of("source", "prechunked-test"))
            ),
            Map.of("source", "prechunked-test")
        )));

        assertThat(storage.documentStore().load("doc-1")).isPresent();
        assertThat(storage.chunkStore().listByDocument("doc-1"))
            .extracting(io.github.lightrag.storage.ChunkStore.ChunkRecord::id)
            .containsExactly("chunk-1", "chunk-2");
    }

    @Test
    void submitIngestPublishesTaskAndStageEventsToRegisteredListener() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .taskEventListener(events::add)
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "task-test"))
        ));

        awaitTerminalTask(rag, taskId);

        assertThat(events).extracting(TaskEvent::eventType).contains(
            TaskEventType.TASK_SUBMITTED,
            TaskEventType.TASK_RUNNING,
            TaskEventType.STAGE_STARTED,
            TaskEventType.STAGE_SUCCEEDED,
            TaskEventType.TASK_SUCCEEDED
        );
    }

    @Test
    void submitIngestPublishesDocumentLifecycleEventsToRegisteredListener() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .taskEventListener(events::add)
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "task-test"))
        ));

        awaitTerminalTask(rag, taskId);

        assertThat(events.stream()
            .filter(event -> event.documentId() != null)
            .map(TaskEvent::eventType)
            .toList()).contains(
            TaskEventType.DOCUMENT_STARTED,
            TaskEventType.DOCUMENT_CHUNKED,
            TaskEventType.DOCUMENT_GRAPH_READY,
            TaskEventType.DOCUMENT_VECTORS_READY,
            TaskEventType.DOCUMENT_COMMITTED
        );
    }

    @Test
    void submitIngestPreChunkedPublishesChunkLifecycleEventsToRegisteredListener() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .taskEventListener(events::add)
            .build();

        var taskId = rag.submitIngestPreChunked(
            WORKSPACE,
            List.of(new PreChunkedDocument(
                "doc-1",
                "Title",
                List.of(
                    new Chunk("chunk-1", "doc-1", "Alice works with Bob", 4, 0, Map.of("source", "prechunked-task-test")),
                    new Chunk("chunk-2", "doc-1", "Bob supports Carol", 4, 1, Map.of("source", "prechunked-task-test"))
                ),
                Map.of("source", "prechunked-task-test")
            )),
            TaskSubmitOptions.defaults()
        );

        awaitTerminalTask(rag, taskId);

        assertThat(events.stream()
            .filter(event -> event.chunkId() != null)
            .map(TaskEvent::eventType)
            .toList()).contains(
            TaskEventType.CHUNK_STARTED,
            TaskEventType.CHUNK_GRAPH_READY,
            TaskEventType.CHUNK_VECTORS_READY,
            TaskEventType.CHUNK_SUCCEEDED
        );
        assertThat(events.stream()
            .filter(event -> event.chunkId() != null)
            .map(TaskEvent::chunkId)
            .toList()).contains("chunk-1", "chunk-2");
    }

    @Test
    void submitIngestPreChunkedPublishesPendingChunkEventsBeforeRuntimeChunkEvents() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .taskEventListener(events::add)
            .build();

        var taskId = rag.submitIngestPreChunked(
            WORKSPACE,
            List.of(new PreChunkedDocument(
                "doc-1",
                "Title",
                List.of(
                    new Chunk("chunk-1", "doc-1", "Alice works with Bob", 4, 0, Map.of("source", "prechunked-task-test")),
                    new Chunk("chunk-2", "doc-1", "Bob supports Carol", 4, 1, Map.of("source", "prechunked-task-test"))
                ),
                Map.of("source", "prechunked-task-test")
            )),
            TaskSubmitOptions.defaults()
        );

        awaitTerminalTask(rag, taskId);

        var chunkEvents = events.stream()
            .filter(event -> event.chunkId() != null)
            .toList();

        assertThat(chunkEvents.stream()
            .filter(event -> event.eventType() == TaskEventType.CHUNK_PENDING)
            .toList())
            .extracting(TaskEvent::scope, TaskEvent::chunkId)
            .containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(TaskEventScope.VECTOR, "chunk-1"),
                org.assertj.core.groups.Tuple.tuple(TaskEventScope.GRAPH, "chunk-1"),
                org.assertj.core.groups.Tuple.tuple(TaskEventScope.VECTOR, "chunk-2"),
                org.assertj.core.groups.Tuple.tuple(TaskEventScope.GRAPH, "chunk-2")
            );

        assertThat(indexOfChunkEvent(chunkEvents, TaskEventType.CHUNK_PENDING, TaskEventScope.VECTOR, "chunk-1"))
            .isLessThan(indexOfChunkEvent(chunkEvents, TaskEventType.CHUNK_VECTORS_READY, TaskEventScope.VECTOR, "chunk-1"));
        assertThat(indexOfChunkEvent(chunkEvents, TaskEventType.CHUNK_PENDING, TaskEventScope.GRAPH, "chunk-1"))
            .isLessThan(indexOfChunkEvent(chunkEvents, TaskEventType.CHUNK_STARTED, TaskEventScope.CHUNK, "chunk-1"));
    }

    @Test
    void submitIngestPublishesEventsToTaskScopedListener() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        var taskId = rag.submitIngest(
            WORKSPACE,
            List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "task-test"))),
            TaskSubmitOptions.builder().listener(events::add).build()
        );

        awaitTerminalTask(rag, taskId);

        assertThat(events)
            .extracting(TaskEvent::taskId)
            .containsOnly(taskId);
        assertThat(events).isNotEmpty();
    }

    @Test
    void submitIngestCreatesPersistentTaskAndStageSnapshots() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "task-test"))
        ));

        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.taskId()).isEqualTo(taskId);
        assertThat(task.workspaceId()).isEqualTo(WORKSPACE);
        assertThat(task.taskType()).isEqualTo(TaskType.INGEST_DOCUMENTS);
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.stages())
            .extracting(TaskStageSnapshot::stage)
            .contains(TaskStage.PREPARING, TaskStage.CHUNKING, TaskStage.PRIMARY_EXTRACTION,
                TaskStage.GRAPH_ASSEMBLY, TaskStage.VECTOR_INDEXING, TaskStage.COMMITTING, TaskStage.COMPLETED);
        assertThat(rag.listTasks(WORKSPACE))
            .extracting(TaskSnapshot::taskId)
            .contains(taskId);
    }

    @Test
    void submitIngestStoresQueueAndTotalTimingMetadata() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "timing-test"))
        ));

        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.metadata()).containsKeys("queueWaitMs", "totalDurationMs");
    }

    @Test
    void submitIngestPersistsTaskDocumentSummary() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "summary-test"))
        ));

        awaitTerminalTask(rag, taskId);

        var documents = rag.listTaskDocuments(WORKSPACE, taskId);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).documentId()).isEqualTo("doc-1");
        assertThat(documents.get(0).status()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(documents.get(0).chunkCount()).isGreaterThan(0);
        assertThat(documents.get(0).entityCount()).isGreaterThan(0);
        assertThat(documents.get(0).chunkVectorCount()).isGreaterThan(0);
    }

    @Test
    void getTaskDocumentReturnsPersistedDocumentSummary() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(InMemoryStorageProvider.create())
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "summary-test"))
        ));

        awaitTerminalTask(rag, taskId);

        var document = rag.getTaskDocument(WORKSPACE, taskId, "doc-1");

        assertThat(document.documentId()).isEqualTo("doc-1");
        assertThat(document.status()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(document.chunkCount()).isGreaterThan(0);
        assertThat(document.entityCount()).isGreaterThan(0);
        assertThat(document.chunkVectorCount()).isGreaterThan(0);
    }

    @Test
    void submitIngestMarksTaskDocumentFailedWhenVectorBuildFailsBeforeCommit() {
        var events = new java.util.concurrent.CopyOnWriteArrayList<TaskEvent>();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FailingEmbeddingModel("embedding boom"))
            .storage(InMemoryStorageProvider.create())
            .taskEventListener(events::add)
            .build();

        var taskId = rag.submitIngest(WORKSPACE, List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of("source", "failure-test"))
        ));

        var task = awaitTerminalTask(rag, taskId);
        var document = rag.getTaskDocument(WORKSPACE, taskId, "doc-1");

        assertThat(task.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(document.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.errorMessage()).contains("embedding boom");
        assertThat(events.stream()
            .filter(event -> "doc-1".equals(event.documentId()))
            .map(TaskEvent::eventType)
            .toList()).contains(TaskEventType.DOCUMENT_FAILED);
    }

    @Test
    void submitRebuildReconstructsGraphAndReportsGraphAssemblyStage() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
        rag.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("alice", "bob");
        rag.deleteByEntity(WORKSPACE, "Alice");

        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("bob");
        assertThat(storage.documentStore().load("doc-1")).isPresent();

        var taskId = rag.submitRebuild(WORKSPACE);
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.taskType()).isEqualTo(TaskType.REBUILD_GRAPH);
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.stages())
            .extracting(TaskStageSnapshot::stage)
            .contains(TaskStage.GRAPH_ASSEMBLY);
        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("alice", "bob");
    }

    @Test
    void taskEnumsIncludeGraphMaterializationValues() {
        var newlyAddedTaskTypes = EnumSet.allOf(TaskType.class);
        newlyAddedTaskTypes.removeAll(EnumSet.of(
            TaskType.INGEST_DOCUMENTS,
            TaskType.INGEST_SOURCES,
            TaskType.REBUILD_GRAPH
        ));
        assertThat(newlyAddedTaskTypes).containsExactlyInAnyOrder(
            TaskType.MATERIALIZE_DOCUMENT_GRAPH,
            TaskType.MATERIALIZE_CHUNK_GRAPH
        );

        var newlyAddedTaskStages = EnumSet.allOf(TaskStage.class);
        newlyAddedTaskStages.removeAll(EnumSet.of(
            TaskStage.PREPARING,
            TaskStage.PARSING,
            TaskStage.CHUNKING,
            TaskStage.PRIMARY_EXTRACTION,
            TaskStage.REFINEMENT_EXTRACTION,
            TaskStage.GRAPH_ASSEMBLY,
            TaskStage.VECTOR_INDEXING,
            TaskStage.COMMITTING,
            TaskStage.COMPLETED
        ));
        assertThat(newlyAddedTaskStages).containsExactlyInAnyOrder(
            TaskStage.SNAPSHOT_LOADING,
            TaskStage.SNAPSHOT_RECOVERY,
            TaskStage.GRAPH_INSPECTION,
            TaskStage.ENTITY_MATERIALIZATION,
            TaskStage.RELATION_MATERIALIZATION,
            TaskStage.VECTOR_REPAIR,
            TaskStage.FINALIZING
        );
    }

    @Test
    void submitDocumentGraphMaterializationPersistsTaskMetadataAndStages() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
        rag.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        storage.documentGraphJournalStore().appendDocument(new io.github.lightrag.storage.DocumentGraphJournalStore.DocumentGraphJournal(
            "doc-1",
            1,
            GraphMaterializationStatus.PARTIAL,
            GraphMaterializationMode.AUTO,
            2,
            1,
            1,
            0,
            FailureStage.RELATION_MATERIALIZATION,
            Instant.now(),
            Instant.now(),
            "relation missing"
        ));
        storage.documentGraphJournalStore().appendChunks("doc-1", List.of(
            new io.github.lightrag.storage.DocumentGraphJournalStore.ChunkGraphJournal(
                "doc-1",
                "doc-1:0",
                1,
                ChunkMergeStatus.FAILED,
                ChunkGraphStatus.PARTIAL,
                List.of("alice", "bob"),
                List.of(relationId("alice", "bob")),
                List.of("alice"),
                List.of(),
                FailureStage.RELATION_MATERIALIZATION,
                Instant.now(),
                "relation missing"
            )
        ));
        ((InMemoryGraphStore) storage.graphStore()).restore(
            List.of(
                ((InMemoryGraphStore) storage.graphStore()).loadEntity("alice").orElseThrow(),
                ((InMemoryGraphStore) storage.graphStore()).loadEntity("bob").orElseThrow()
            ),
            List.of()
        );

        var taskId = rag.submitDocumentGraphMaterialization(WORKSPACE, "doc-1", GraphMaterializationMode.REPAIR);
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.taskType()).isEqualTo(TaskType.MATERIALIZE_DOCUMENT_GRAPH);
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.metadata()).containsEntry("documentId", "doc-1");
        assertThat(task.metadata()).containsEntry("requestedMode", "REPAIR");
        assertThat(task.metadata()).containsEntry("executedMode", "REPAIR");
        assertThat(task.metadata()).containsEntry("finalStatus", "MERGED");
        assertThat(task.stages())
            .extracting(TaskStageSnapshot::stage)
            .contains(
                TaskStage.SNAPSHOT_LOADING,
                TaskStage.GRAPH_INSPECTION,
                TaskStage.ENTITY_MATERIALIZATION,
                TaskStage.RELATION_MATERIALIZATION,
                TaskStage.FINALIZING,
                TaskStage.COMPLETED
            );
    }

    @Test
    void submitChunkGraphMaterializationPersistsChunkMetadataAndStages() {
        var storage = InMemoryStorageProvider.create();
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
        rag.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        storage.documentGraphJournalStore().appendDocument(new io.github.lightrag.storage.DocumentGraphJournalStore.DocumentGraphJournal(
            "doc-1",
            1,
            GraphMaterializationStatus.PARTIAL,
            GraphMaterializationMode.AUTO,
            2,
            1,
            1,
            0,
            FailureStage.RELATION_MATERIALIZATION,
            Instant.now(),
            Instant.now(),
            "relation missing"
        ));
        storage.documentGraphJournalStore().appendChunks("doc-1", List.of(
            new io.github.lightrag.storage.DocumentGraphJournalStore.ChunkGraphJournal(
                "doc-1",
                "doc-1:0",
                1,
                ChunkMergeStatus.FAILED,
                ChunkGraphStatus.PARTIAL,
                List.of("alice", "bob"),
                List.of(relationId("alice", "bob")),
                List.of("alice"),
                List.of(),
                FailureStage.RELATION_MATERIALIZATION,
                Instant.now(),
                "relation missing"
            )
        ));
        ((InMemoryGraphStore) storage.graphStore()).restore(
            List.of(
                ((InMemoryGraphStore) storage.graphStore()).loadEntity("alice").orElseThrow(),
                ((InMemoryGraphStore) storage.graphStore()).loadEntity("bob").orElseThrow()
            ),
            List.of()
        );

        var taskId = rag.submitChunkGraphMaterialization(WORKSPACE, "doc-1", "doc-1:0", GraphChunkAction.REPAIR);
        var task = awaitTerminalTask(rag, taskId);

        assertThat(task.taskType()).isEqualTo(TaskType.MATERIALIZE_CHUNK_GRAPH);
        assertThat(task.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.metadata()).containsEntry("documentId", "doc-1");
        assertThat(task.metadata()).containsEntry("chunkId", "doc-1:0");
        assertThat(task.metadata()).containsEntry("requestedAction", "REPAIR");
        assertThat(task.metadata()).containsEntry("finalStatus", "MATERIALIZED");
        assertThat(task.stages())
            .extracting(TaskStageSnapshot::stage)
            .contains(
                TaskStage.PREPARING,
                TaskStage.SNAPSHOT_LOADING,
                TaskStage.GRAPH_INSPECTION,
                TaskStage.COMPLETED
            );
    }

    private static TaskSnapshot awaitTerminalTask(LightRag rag, String taskId) {
        var deadline = Instant.now().plus(Duration.ofSeconds(5));
        TaskSnapshot snapshot = rag.getTask(WORKSPACE, taskId);
        while (!snapshot.status().isTerminal() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Test interrupted", exception);
            }
            snapshot = rag.getTask(WORKSPACE, taskId);
        }
        assertThat(snapshot.status().isTerminal()).isTrue();
        return snapshot;
    }

    private static int indexOfChunkEvent(
        List<TaskEvent> events,
        TaskEventType eventType,
        TaskEventScope scope,
        String chunkId
    ) {
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (eventType == event.eventType() && scope == event.scope() && chunkId.equals(event.chunkId())) {
                return index;
            }
        }
        throw new AssertionError("missing chunk event: " + eventType + ", scope=" + scope + ", chunkId=" + chunkId);
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            var prompt = request.userPrompt() == null ? "" : request.userPrompt().toLowerCase(Locale.ROOT);
            if (prompt.contains("should_continue")) {
                return "no";
            }
            return "{\"entities\":[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Alice\",\"aliases\":[]},{\"name\":\"Bob\",\"type\":\"person\",\"description\":\"Bob\",\"aliases\":[]}],\"relations\":[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\",\"type\":\"works_with\",\"description\":\"works with\",\"weight\":1.0}]}";
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
        }
    }

    private static final class FailingEmbeddingModel implements EmbeddingModel {
        private final String message;

        private FailingEmbeddingModel(String message) {
            this.message = message;
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            throw new IllegalStateException(message);
        }
    }
}
