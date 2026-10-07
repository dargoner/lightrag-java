package io.github.lightrag.storage;

import java.util.List;

/**
 * Backend-neutral lifecycle capability for whole-workspace graphs, for maintenance paths that run
 * outside a workspace's storage provider: resolving the graph identifier a workspace maps to,
 * listing the graph identifiers of a workspace family, and dropping a workspace graph. Each graph
 * backend expresses the operations in its own terms (an Apache AGE graph, a Neo4j workspace
 * subgraph, ...).
 */
public interface WorkspaceGraphLifecycle {

    /** The graph identifier the workspace maps to on this backend (for comparison and logs). */
    String workspaceGraphId(String workspaceId);

    /**
     * Graph identifiers of the workspace family matching the given raw workspace prefix (for
     * example {@code "app-kb-"}); matched in the same textual form the backend stores them.
     */
    List<String> listWorkspaceGraphIds(String workspaceIdPrefix);

    /**
     * Drops the whole graph identified by {@code workspaceGraphId}. Idempotent: returns
     * {@code false} when the graph is already gone instead of failing.
     */
    boolean dropWorkspaceGraph(String workspaceGraphId);
}
