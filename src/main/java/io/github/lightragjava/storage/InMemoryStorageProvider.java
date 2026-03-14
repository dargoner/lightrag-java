package io.github.lightragjava.storage;

import io.github.lightragjava.storage.memory.InMemoryChunkStore;
import io.github.lightragjava.storage.memory.InMemoryDocumentStore;
import io.github.lightragjava.storage.memory.InMemoryGraphStore;
import io.github.lightragjava.storage.memory.InMemoryVectorStore;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class InMemoryStorageProvider implements StorageProvider {
    private final DocumentStore documentStore;
    private final ChunkStore chunkStore;
    private final GraphStore graphStore;
    private final VectorStore vectorStore;
    private final SnapshotStore snapshotStore;

    public InMemoryStorageProvider() {
        this.documentStore = new InMemoryDocumentStore();
        this.chunkStore = new InMemoryChunkStore();
        this.graphStore = new InMemoryGraphStore();
        this.vectorStore = new InMemoryVectorStore();
        this.snapshotStore = new InMemorySnapshotStore();
    }

    public static InMemoryStorageProvider create() {
        return new InMemoryStorageProvider();
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
    public SnapshotStore snapshotStore() {
        return snapshotStore;
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
                Map.copyOf(source.vectors())
            );
        }
    }
}
