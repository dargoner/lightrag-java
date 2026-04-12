package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagTaskApiTest {
    private static final String WORKSPACE = "default";

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
            .containsExactly("entity:alice", "entity:bob");
        rag.deleteByEntity(WORKSPACE, "Alice");

        assertThat(storage.graphStore().allEntities())
            .extracting(entity -> entity.id())
            .containsExactly("entity:bob");
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
            .containsExactly("entity:alice", "entity:bob");
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
}
