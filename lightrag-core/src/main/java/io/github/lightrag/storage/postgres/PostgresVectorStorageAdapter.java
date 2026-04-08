package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class PostgresVectorStorageAdapter implements VectorStorageAdapter {
    private static final List<String> DEFAULT_NAMESPACES = List.of("chunks", "entities", "relations");

    private final PostgresStorageProvider postgresProvider;

    public PostgresVectorStorageAdapter(PostgresStorageProvider postgresProvider) {
        this.postgresProvider = Objects.requireNonNull(postgresProvider, "postgresProvider");
    }

    @Override
    public VectorStore vectorStore() {
        return postgresProvider.vectorStore();
    }

    @Override
    public VectorSnapshot captureSnapshot() {
        var namespaces = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        var vectorStore = postgresProvider.vectorStore();
        for (var namespace : DEFAULT_NAMESPACES) {
            namespaces.put(namespace, vectorStore.list(namespace));
        }
        return new VectorSnapshot(namespaces);
    }

    @Override
    public void apply(StagedVectorWrites writes) {
        Objects.requireNonNull(writes, "writes");
        // Postgres baseline vectors are already written through the transactional vector store
        // exposed by the relational adapter, so there is no post-commit projection step here.
    }

    @Override
    public void restore(VectorSnapshot snapshot) {
        var source = Objects.requireNonNull(snapshot, "snapshot");
        postgresProvider.restore(new SnapshotStore.Snapshot(
            postgresProvider.documentStore().list(),
            postgresProvider.chunkStore().list(),
            postgresProvider.graphStore().allEntities(),
            postgresProvider.graphStore().allRelations(),
            source.namespaces(),
            postgresProvider.documentStatusStore().list()
        ));
    }

}
