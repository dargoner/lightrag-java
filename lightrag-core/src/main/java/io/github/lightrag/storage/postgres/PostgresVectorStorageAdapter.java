package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PostgresVectorStorageAdapter implements VectorStorageAdapter {
    private static final List<String> DEFAULT_NAMESPACES = List.of("chunks", "entities", "relations");

    /**
     * Sentinel for "scoped pre-image with nothing to restore": {@link #apply} is a no-op because Postgres baseline
     * vectors are written through the transactional vector store, so there is no post-commit projection to roll
     * back. Must be captured as {@code Optional.of(EMPTY_PRE_IMAGE)} — {@code Optional.empty()} would mean "scoped
     * pre-images not supported" and force a whole-workspace {@link #captureSnapshot()}.
     */
    private static final PreImage EMPTY_PRE_IMAGE = new PreImage() {
    };

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
        var documentGraphState = DocumentGraphStateSupport.capture(
            postgresProvider.documentGraphSnapshotStore(),
            postgresProvider.documentGraphJournalStore(),
            java.util.List.of(),
            postgresProvider.documentStore().list(),
            postgresProvider.documentStatusStore().list()
        );
        postgresProvider.restore(new SnapshotStore.Snapshot(
            postgresProvider.documentStore().list(),
            postgresProvider.chunkStore().list(),
            postgresProvider.graphStore().allEntities(),
            postgresProvider.graphStore().allRelations(),
            source.namespaces(),
            postgresProvider.documentStatusStore().list(),
            documentGraphState.documentSnapshots(),
            documentGraphState.chunkSnapshots(),
            documentGraphState.documentJournals(),
            documentGraphState.chunkJournals()
        ));
    }

    @Override
    public Optional<PreImage> capturePreImage(Map<String, List<String>> idsByNamespace) {
        return Optional.of(EMPTY_PRE_IMAGE);
    }

    @Override
    public void restorePreImage(PreImage preImage) {
        if (preImage != EMPTY_PRE_IMAGE) {
            throw new IllegalArgumentException("unexpected pre-image payload: " + preImage);
        }
    }

}
