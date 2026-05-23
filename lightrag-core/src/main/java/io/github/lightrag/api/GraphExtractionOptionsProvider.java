package io.github.lightrag.api;

import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface GraphExtractionOptionsProvider {
    Optional<GraphExtractionOptions> resolve(WorkspaceScope scope);

    static GraphExtractionOptionsProvider none() {
        return ignored -> Optional.empty();
    }

    static GraphExtractionOptionsProvider fixed(GraphExtractionOptions options) {
        var resolvedOptions = Objects.requireNonNull(options, "options");
        return ignored -> Optional.of(resolvedOptions);
    }
}
