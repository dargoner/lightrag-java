package io.github.lightrag.storage;

import io.github.lightrag.api.TaskStage;
import io.github.lightrag.api.TaskStageStatus;
import io.github.lightrag.api.TaskStatus;
import io.github.lightrag.api.TaskType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskStoreTest {
    @Test
    void providerExposesTaskStoresAndPersistsTaskSnapshots() {
        var provider = InMemoryStorageProvider.create();
        var requestedAt = Instant.parse("2026-04-09T12:00:00Z");
        var startedAt = requestedAt.plusSeconds(1);
        var stageStartedAt = requestedAt.plusSeconds(2);

        provider.taskStore().save(new TaskStore.TaskRecord(
            "task-1",
            "default",
            TaskType.INGEST_DOCUMENTS,
            TaskStatus.RUNNING,
            requestedAt,
            startedAt,
            null,
            "running",
            null,
            false,
            Map.of("documentCount", "1")
        ));
        provider.taskStageStore().save(new TaskStageStore.TaskStageRecord(
            "task-1",
            TaskStage.CHUNKING,
            TaskStageStatus.RUNNING,
            2,
            stageStartedAt,
            null,
            "chunking doc-1",
            null
        ));

        assertThat(provider.taskStore().load("task-1"))
            .get()
            .extracting(TaskStore.TaskRecord::workspaceId, TaskStore.TaskRecord::taskType, TaskStore.TaskRecord::status)
            .containsExactly("default", TaskType.INGEST_DOCUMENTS, TaskStatus.RUNNING);
        assertThat(provider.taskStageStore().listByTask("task-1"))
            .singleElement()
            .extracting(TaskStageStore.TaskStageRecord::stage, TaskStageStore.TaskStageRecord::status)
            .containsExactly(TaskStage.CHUNKING, TaskStageStatus.RUNNING);
    }
}
