package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;

import java.util.Objects;

public final class FixedWorkspaceStorageProvider implements WorkspaceStorageProvider {
    private final AtomicStorageProvider delegate;

    public FixedWorkspaceStorageProvider(AtomicStorageProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
        Objects.requireNonNull(scope, "scope");
        return delegate;
    }

    @Override
    public void close() {
        // Legacy storage providers are not closable today.
    }
}

