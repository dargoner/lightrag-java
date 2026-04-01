package io.github.lightrag.storage;

import io.github.lightrag.storage.memory.InMemoryChunkStore;
import io.github.lightrag.storage.memory.InMemoryDocumentStore;
import io.github.lightrag.storage.memory.InMemoryDocumentStatusStore;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.storage.memory.InMemoryVectorStore;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class InMemoryStorageProvider implements AtomicStorageProvider {
    private final ReadWriteLock lock;
    private final InMemoryDocumentStore documentStore;
    private final InMemoryChunkStore chunkStore;
    private final InMemoryGraphStore graphStore;
    private final InMemoryVectorStore vectorStore;
    private final InMemoryDocumentStatusStore documentStatusStore;
    private final SnapshotStore snapshotStore;

    public InMemoryStorageProvider() {
        this(new InMemorySnapshotStore());
    }

    public InMemoryStorageProvider(SnapshotStore snapshotStore) {
        this.lock = new ReentrantReadWriteLock(true);
        this.documentStore = new InMemoryDocumentStore(lock);
        this.chunkStore = new InMemoryChunkStore(lock);
        this.graphStore = new InMemoryGraphStore(lock);
        this.vectorStore = new InMemoryVectorStore(lock);
        this.documentStatusStore = new InMemoryDocumentStatusStore(lock);
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    }

    public static InMemoryStorageProvider create() {
        return new InMemoryStorageProvider();
    }

    public static InMemoryStorageProvider create(SnapshotStore snapshotStore) {
        return new InMemoryStorageProvider(snapshotStore);
    }

    @Override
    public DocumentStore documentStore() {
        return documentStore;
    }

    @Override
    public ChunkStore chunkStore() {
        return chunkStore;
    }

    @Override
    public GraphStore graphStore() {
        return graphStore;
    }

    @Override
    public VectorStore vectorStore() {
        return vectorStore;
    }

    @Override
    public DocumentStatusStore documentStatusStore() {
        return documentStatusStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        var snapshot = snapshot();
        try {
            return Objects.requireNonNull(operation, "operation").execute(new AtomicView(
                documentStore,
                chunkStore,
                graphStore,
                vectorStore,
                documentStatusStore
            ));
        } catch (RuntimeException failure) {
            restore(snapshot, failure);
            throw failure;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            restoreStores(Objects.requireNonNull(snapshot, "snapshot"));
        } finally {
            writeLock.unlock();
        }
    }

    private SnapshotStore.Snapshot snapshot() {
        return new SnapshotStore.Snapshot(
            documentStore.snapshot(),
            chunkStore.snapshot(),
            graphStore.snapshotEntities(),
            graphStore.snapshotRelations(),
            vectorStore.snapshot(),
            documentStatusStore.snapshot()
        );
    }

    private void restore(SnapshotStore.Snapshot snapshot, RuntimeException failure) {
        RuntimeException rollbackFailure = null;

        try {
            vectorStore.restore(snapshot.vectors());
        } catch (RuntimeException exception) {
            rollbackFailure = exception;
        }

        try {
            graphStore.restore(snapshot.entities(), snapshot.relations());
        } catch (RuntimeException exception) {
            if (rollbackFailure == null) {
                rollbackFailure = exception;
            } else {
                rollbackFailure.addSuppressed(exception);
            }
        }

        try {
            chunkStore.restore(snapshot.chunks());
        } catch (RuntimeException exception) {
            if (rollbackFailure == null) {
                rollbackFailure = exception;
            } else {
                rollbackFailure.addSuppressed(exception);
            }
        }

        try {
            documentStore.restore(snapshot.documents());
        } catch (RuntimeException exception) {
            if (rollbackFailure == null) {
                rollbackFailure = exception;
            } else {
                rollbackFailure.addSuppressed(exception);
            }
        }

        try {
            documentStatusStore.restore(snapshot.documentStatuses());
        } catch (RuntimeException exception) {
            if (rollbackFailure == null) {
                rollbackFailure = exception;
            } else {
                rollbackFailure.addSuppressed(exception);
            }
        }

        if (rollbackFailure != null) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private void restoreStores(SnapshotStore.Snapshot snapshot) {
        vectorStore.restore(snapshot.vectors());
        graphStore.restore(snapshot.entities(), snapshot.relations());
        chunkStore.restore(snapshot.chunks());
        documentStore.restore(snapshot.documents());
        documentStatusStore.restore(snapshot.documentStatuses());
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }

    private static final class InMemorySnapshotStore implements SnapshotStore {
        private final ConcurrentNavigableMap<String, Snapshot> snapshots = new ConcurrentSkipListMap<>();

        @Override
        public void save(Path path, Snapshot snapshot) {
            snapshots.put(normalize(path), copySnapshot(snapshot));
        }

        @Override
        public Snapshot load(Path path) {
            var snapshot = snapshots.get(normalize(path));
            if (snapshot == null) {
                throw new NoSuchElementException("No snapshot stored for path: " + path);
            }
            return copySnapshot(snapshot);
        }

        @Override
        public List<Path> list() {
            return snapshots.keySet().stream()
                .sorted(Comparator.naturalOrder())
                .map(Path::of)
                .toList();
        }

        private static String normalize(Path path) {
            return Objects.requireNonNull(path, "path").normalize().toString();
        }

        private static Snapshot copySnapshot(Snapshot snapshot) {
            var source = Objects.requireNonNull(snapshot, "snapshot");
            return new Snapshot(
                source.documents(),
                source.chunks(),
                source.entities(),
                source.relations(),
                Map.copyOf(source.vectors()),
                source.documentStatuses()
            );
        }
    }
}
