package io.github.lightrag.storage;

import io.github.lightrag.api.WorkspaceScope;

public interface WorkspaceStorageProvider extends AutoCloseable {
    AtomicStorageProvider forWorkspace(WorkspaceScope scope);

    @Override
    void close();
}

