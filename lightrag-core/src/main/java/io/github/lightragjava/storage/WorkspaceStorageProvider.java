package io.github.lightragjava.storage;

import io.github.lightragjava.api.WorkspaceScope;

public interface WorkspaceStorageProvider extends AutoCloseable {
    AtomicStorageProvider forWorkspace(WorkspaceScope scope);

    @Override
    void close();
}

