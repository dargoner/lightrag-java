package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.api.WorkspaceScope;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.WorkspaceStorageProvider;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class ArcadeWorkspaceStorageProvider implements WorkspaceStorageProvider {
    private final ArcadeDbConfig config;
    private final SnapshotStore snapshotStore;
    private final ConcurrentHashMap<String, ArcadeStorageProvider> providers = new ConcurrentHashMap<>();

    public ArcadeWorkspaceStorageProvider(ArcadeDbConfig config, SnapshotStore snapshotStore) {
        this.config = Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    }

    @Override
    public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
        var workspace = Objects.requireNonNull(scope, "scope").workspaceId();
        return providers.computeIfAbsent(workspace, id -> new ArcadeStorageProvider(config, snapshotStore, id));
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var provider : providers.values()) {
            try {
                provider.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        providers.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
