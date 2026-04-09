package io.github.lightrag.api;

import java.time.Instant;
import java.util.Objects;

public record TaskStageSnapshot(
    TaskStage stage,
    TaskStageStatus status,
    int sequence,
    Instant startedAt,
    Instant finishedAt,
    String message,
    String errorMessage
) {
    public TaskStageSnapshot {
        stage = Objects.requireNonNull(stage, "stage");
        status = Objects.requireNonNull(status, "status");
        message = message == null ? "" : message.strip();
        errorMessage = errorMessage == null ? null : errorMessage.strip();
    }
}
