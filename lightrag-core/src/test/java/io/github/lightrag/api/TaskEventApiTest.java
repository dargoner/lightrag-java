package io.github.lightrag.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventApiTest {
    @Test
    void taskEventCarriesStableEnvelopeFields() {
        var now = Instant.parse("2026-04-19T08:00:00Z");
        var event = new TaskEvent(
            "event-1",
            TaskEventType.TASK_SUBMITTED,
            TaskEventScope.TASK,
            now,
            "workspace-a",
            "task-1",
            TaskType.INGEST_DOCUMENTS,
            null,
            null,
            null,
            "queued",
            "task queued",
            Map.of("documentCount", "2")
        );

        assertThat(event.eventId()).isEqualTo("event-1");
        assertThat(event.eventType()).isEqualTo(TaskEventType.TASK_SUBMITTED);
        assertThat(event.scope()).isEqualTo(TaskEventScope.TASK);
        assertThat(event.taskType()).isEqualTo(TaskType.INGEST_DOCUMENTS);
        assertThat(event.attributes()).containsEntry("documentCount", "2");
    }
}
