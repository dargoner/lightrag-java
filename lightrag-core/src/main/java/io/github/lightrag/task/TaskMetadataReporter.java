package io.github.lightrag.task;

import java.util.Map;
import java.util.Objects;

@FunctionalInterface
public interface TaskMetadataReporter {
    void updateMetadata(Map<String, String> metadata);

    static TaskMetadataReporter noop() {
        return metadata -> {
            Objects.requireNonNull(metadata, "metadata");
        };
    }
}
