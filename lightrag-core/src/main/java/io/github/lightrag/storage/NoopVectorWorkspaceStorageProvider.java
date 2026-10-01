package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;

import java.util.Objects;

/** Workspace-scoped counterpart of {@link NoopVectorStorageProvider}: every resolved workspace gets the noop vector route. */
public final class NoopVectorWorkspaceStorageProvider implements WorkspaceStorageProvider {
    private final WorkspaceStorageProvider delegate;

    public NoopVectorWorkspaceStorageProvider(WorkspaceStorageProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
        Objects.requireNonNull(scope, "scope");
        return new NoopVectorStorageProvider(delegate.forWorkspace(scope));
    }

    @Override
    public void close() {
        delegate.close();
    }
}
