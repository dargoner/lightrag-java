package io.github.lightrag.api;

import io.github.lightrag.ontology.OntologyExtractionOverlay;

import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface GraphExtractionOptionsProvider {
    Optional<GraphExtractionOptions> resolve(WorkspaceScope scope);

    /**
     * Optional ontology-driven extraction overlay for the given scope.
     *
     * <p>This default returns an empty overlay; hosts that manage ontology assets
     * can override it to inject directed relation patterns and attribute blocks
     * into the extraction prompt.
     */
    default Optional<OntologyExtractionOverlay> ontologyExtractionOverlay(WorkspaceScope scope) {
        return Optional.empty();
    }

    /**
     * Notifies the provider that the workspace graph content has been modified by a
     * non-extraction write (restore, import, manual graph edit, or document deletion).
     *
     * <p>The default implementation is a no-op; hosts that track ontology consistency
     * can override it to invalidate cached consistency state.
     *
     * @param scope the workspace scope
     */
    default void markOntologyDirty(WorkspaceScope scope) {
        // no-op by default
    }

    static GraphExtractionOptionsProvider none() {
        return ignored -> Optional.empty();
    }

    static GraphExtractionOptionsProvider fixed(GraphExtractionOptions options) {
        var resolvedOptions = Objects.requireNonNull(options, "options");
        return ignored -> Optional.of(resolvedOptions);
    }
}
