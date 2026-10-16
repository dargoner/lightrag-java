package io.github.lightrag.api;

import io.github.lightrag.ontology.OntologyExtractionOverlay;
import io.github.lightrag.ontology.OntologyTaskContext;
import io.github.lightrag.ontology.UnmatchedLabel;

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

    /**
     * Notifies the provider about a label that did not conform to the ontology
     * snapshot bound to the running pipeline.
     *
     * <p>The callback receives the final context actually used by the pipeline,
     * including contexts restored from stored task metadata, so hosts can attribute
     * unmatched labels to the exact ontology snapshot. The default implementation is
     * a no-op.
     *
     * @param scope     the workspace scope
     * @param context   the ontology task context used by the pipeline
     * @param unmatched the unmatched label
     */
    default void onOntologyUnmatched(WorkspaceScope scope, OntologyTaskContext context, UnmatchedLabel unmatched) {
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
