package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;

import java.util.List;
import java.util.Objects;

public interface WorkspaceStorageProvider extends AutoCloseable {
    AtomicStorageProvider forWorkspace(WorkspaceScope scope);

    /**
     * Resolves one store set covering every scope at once, so a single query pipeline can batch its
     * reads across the workspace set (one keyword extraction, one embedding round, IN-batched
     * storage reads, one merge/rerank) instead of fanning out per workspace. A single-element list
     * is equivalent to {@link #forWorkspace(WorkspaceScope)}; providers whose backends cannot read
     * across workspaces keep the loud default rejection.
     */
    default AtomicStorageProvider forWorkspaces(List<WorkspaceScope> scopes) {
        var normalized = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }
        if (normalized.size() == 1) {
            return forWorkspace(normalized.get(0));
        }
        throw new UnsupportedOperationException(
            "workspace-set queries are not supported by " + getClass().getName());
    }

    @Override
    void close();
}

