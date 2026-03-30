package io.github.lightragjava.spring.boot;

import io.github.lightragjava.api.WorkspaceScope;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.InMemoryStorageProvider;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.WorkspaceStorageProvider;
import io.github.lightragjava.storage.neo4j.Neo4jGraphConfig;
import io.github.lightragjava.storage.neo4j.PostgresNeo4jStorageProvider;
import io.github.lightragjava.storage.postgres.PostgresStorageConfig;
import io.github.lightragjava.storage.postgres.PostgresStorageProvider;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class SpringWorkspaceStorageProvider implements WorkspaceStorageProvider {
    private static final int WORKSPACE_HASH_LENGTH = 8;

    private final LightRagProperties properties;
    private final StorageProvider existingStorageProvider;
    private final DataSource applicationDataSource;
    private final SnapshotStore baseSnapshotStore;
    private final WorkspaceProviderFactory managedProviderFactory;
    private final ConcurrentMap<String, AtomicStorageProvider> providerCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantReadWriteLock> lockCache = new ConcurrentHashMap<>();

    public SpringWorkspaceStorageProvider(
        ObjectProvider<StorageProvider> storageProvider,
        ObjectProvider<DataSource> dataSource,
        SnapshotStore snapshotStore,
        LightRagProperties properties
    ) {
        this(
            properties,
            storageProvider.getIfAvailable(),
            dataSource.getIfAvailable(),
            snapshotStore,
            null
        );
    }

    SpringWorkspaceStorageProvider(
        LightRagProperties properties,
        StorageProvider existingStorageProvider,
        DataSource applicationDataSource,
        SnapshotStore snapshotStore,
        WorkspaceProviderFactory managedProviderFactory
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.existingStorageProvider = existingStorageProvider;
        this.applicationDataSource = applicationDataSource;
        this.baseSnapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.managedProviderFactory = managedProviderFactory == null ? this::createManagedProvider : managedProviderFactory;
    }

    @Override
    public AtomicStorageProvider forWorkspace(WorkspaceScope scope) {
        var workspaceId = Objects.requireNonNull(scope, "scope").workspaceId();
        var cached = providerCache.get(workspaceId);
        if (cached != null) {
            return cached;
        }
        synchronized (providerCache) {
            cached = providerCache.get(workspaceId);
            if (cached != null) {
                return cached;
            }
            if (providerCache.size() >= maxActiveWorkspaces()) {
                throw new IllegalStateException("workspace cache limit exceeded");
            }
            var created = createProvider(workspaceId);
            providerCache.put(workspaceId, created);
            return created;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var provider : providerCache.values()) {
            if (!(provider instanceof AutoCloseable closeable)) {
                continue;
            }
            try {
                closeable.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            } catch (Exception exception) {
                if (failure == null) {
                    failure = new IllegalStateException("failed to close workspace storage provider", exception);
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        providerCache.clear();
        lockCache.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private AtomicStorageProvider createProvider(String workspaceId) {
        if (isDefaultWorkspace(workspaceId) && existingStorageProvider != null) {
            return requireAtomicStorageProvider(existingStorageProvider);
        }
        if (!isDefaultWorkspace(workspaceId) && isCustomStorageProvider(existingStorageProvider)) {
            throw new IllegalStateException("non-default workspaces require starter-managed storage providers");
        }
        var scope = new WorkspaceScope(workspaceId);
        var lock = lockCache.computeIfAbsent(workspaceId, ignored -> new ReentrantReadWriteLock(true));
        SnapshotStore snapshotStore = configuredSnapshotPath()
            .<SnapshotStore>map(path -> new WorkspaceSnapshotStore(
                baseSnapshotStore,
                path,
                deriveSnapshotPath(path, workspaceId)
            ))
            .orElse(baseSnapshotStore);
        return managedProviderFactory.create(scope, applicationDataSource, lock, snapshotStore);
    }

    private AtomicStorageProvider createManagedProvider(
        WorkspaceScope scope,
        DataSource dataSource,
        ReentrantReadWriteLock lock,
        SnapshotStore snapshotStore
    ) {
        return switch (properties.getStorage().getType()) {
            case IN_MEMORY -> InMemoryStorageProvider.create(snapshotStore);
            case POSTGRES -> dataSource != null
                ? new PostgresStorageProvider(dataSource, postgresConfig(), snapshotStore, scope.workspaceId())
                : new PostgresStorageProvider(postgresConfig(), snapshotStore, scope.workspaceId());
            case POSTGRES_NEO4J -> new PostgresNeo4jStorageProvider(
                dataSource,
                postgresConfig(),
                neo4jConfig(),
                snapshotStore,
                scope
            );
        };
    }

    private PostgresStorageConfig postgresConfig() {
        var postgres = properties.getStorage().getPostgres();
        if (postgres.getVectorDimensions() == null) {
            throw new IllegalStateException("lightrag.storage.postgres.vector-dimensions is required");
        }
        return new PostgresStorageConfig(
            requireValue(postgres.getJdbcUrl(), "lightrag.storage.postgres.jdbc-url"),
            requireValue(postgres.getUsername(), "lightrag.storage.postgres.username"),
            requireValue(postgres.getPassword(), "lightrag.storage.postgres.password"),
            requireValue(postgres.getSchema(), "lightrag.storage.postgres.schema"),
            postgres.getVectorDimensions(),
            requireValue(postgres.getTablePrefix(), "lightrag.storage.postgres.table-prefix")
        );
    }

    private Neo4jGraphConfig neo4jConfig() {
        var neo4j = properties.getStorage().getNeo4j();
        return new Neo4jGraphConfig(
            requireValue(neo4j.getUri(), "lightrag.storage.neo4j.uri"),
            requireValue(neo4j.getUsername(), "lightrag.storage.neo4j.username"),
            requireValue(neo4j.getPassword(), "lightrag.storage.neo4j.password"),
            requireValue(neo4j.getDatabase(), "lightrag.storage.neo4j.database")
        );
    }

    private Optional<Path> configuredSnapshotPath() {
        var configuredSnapshotPath = properties.getSnapshotPath();
        if (configuredSnapshotPath == null || configuredSnapshotPath.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(configuredSnapshotPath.strip()).toAbsolutePath().normalize());
    }

    private Path deriveSnapshotPath(Path basePath, String workspaceId) {
        if (isDefaultWorkspace(workspaceId)) {
            return basePath;
        }
        var fileName = basePath.getFileName().toString();
        var extensionIndex = fileName.lastIndexOf('.');
        var baseName = extensionIndex >= 0 ? fileName.substring(0, extensionIndex) : fileName;
        var extension = extensionIndex >= 0 ? fileName.substring(extensionIndex) : "";
        var derivedName = baseName + "-" + slug(workspaceId) + "-" + shortHash(workspaceId) + extension;
        return basePath.resolveSibling(derivedName).toAbsolutePath().normalize();
    }

    private boolean isDefaultWorkspace(String workspaceId) {
        return workspaceId.equals(defaultWorkspaceId());
    }

    private String defaultWorkspaceId() {
        var defaultId = properties.getWorkspace().getDefaultId();
        if (defaultId == null || defaultId.isBlank()) {
            throw new IllegalStateException("lightrag.workspace.default-id must not be blank");
        }
        return defaultId.strip();
    }

    private int maxActiveWorkspaces() {
        var value = properties.getWorkspace().getMaxActiveWorkspaces();
        if (value <= 0) {
            throw new IllegalStateException("lightrag.workspace.max-active-workspaces must be positive");
        }
        return value;
    }

    private static AtomicStorageProvider requireAtomicStorageProvider(StorageProvider storageProvider) {
        if (!(storageProvider instanceof AtomicStorageProvider atomicStorageProvider)) {
            throw new IllegalStateException("storageProvider must implement AtomicStorageProvider");
        }
        return atomicStorageProvider;
    }

    private static boolean isCustomStorageProvider(StorageProvider storageProvider) {
        if (storageProvider == null) {
            return false;
        }
        return !(storageProvider instanceof InMemoryStorageProvider)
            && !(storageProvider instanceof PostgresStorageProvider)
            && !(storageProvider instanceof PostgresNeo4jStorageProvider);
    }

    private static String slug(String workspaceId) {
        var normalized = Objects.requireNonNull(workspaceId, "workspaceId")
            .strip()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_+", "")
            .replaceAll("_+$", "");
        return normalized.isEmpty() ? "workspace" : normalized;
    }

    private static String shortHash(String workspaceId) {
        var hex = Integer.toUnsignedString(Objects.requireNonNull(workspaceId, "workspaceId").hashCode(), 16);
        if (hex.length() >= WORKSPACE_HASH_LENGTH) {
            return hex.substring(0, WORKSPACE_HASH_LENGTH);
        }
        return "0".repeat(WORKSPACE_HASH_LENGTH - hex.length()) + hex;
    }

    private static String requireValue(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required");
        }
        return value;
    }

    @FunctionalInterface
    interface WorkspaceProviderFactory {
        AtomicStorageProvider create(
            WorkspaceScope scope,
            DataSource applicationDataSource,
            ReentrantReadWriteLock lock,
            SnapshotStore snapshotStore
        );
    }

    private static final class WorkspaceSnapshotStore implements SnapshotStore {
        private final SnapshotStore delegate;
        private final Path basePath;
        private final Path derivedPath;
        private final ConcurrentSkipListSet<Path> knownSnapshotPaths = new ConcurrentSkipListSet<>();

        private WorkspaceSnapshotStore(SnapshotStore delegate, Path basePath, Path derivedPath) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.basePath = Objects.requireNonNull(basePath, "basePath");
            this.derivedPath = Objects.requireNonNull(derivedPath, "derivedPath");
        }

        @Override
        public void save(Path path, Snapshot snapshot) {
            var resolvedPath = resolve(path);
            delegate.save(resolvedPath, snapshot);
            knownSnapshotPaths.add(resolvedPath);
        }

        @Override
        public Snapshot load(Path path) {
            var resolvedPath = resolve(path);
            var snapshot = delegate.load(resolvedPath);
            knownSnapshotPaths.add(resolvedPath);
            return snapshot;
        }

        @Override
        public List<Path> list() {
            return List.copyOf(knownSnapshotPaths);
        }

        private Path resolve(Path path) {
            var normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (normalized.equals(basePath)) {
                return derivedPath;
            }
            return normalized;
        }
    }
}
