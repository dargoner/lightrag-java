package io.github.lightrag.indexing;

import io.github.lightrag.api.TaskStage;

public interface IndexingProgressListener {
    IndexingProgressListener NOOP = new IndexingProgressListener() {
    };

    default void onStageStarted(TaskStage stage, String message) {
    }

    default void onStageSucceeded(TaskStage stage, String message) {
    }

    default void onStageSkipped(TaskStage stage, String message) {
    }

    static IndexingProgressListener noop() {
        return NOOP;
    }
}
