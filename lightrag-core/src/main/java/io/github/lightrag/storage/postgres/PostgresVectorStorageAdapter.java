package io.github.lightrag.storage.postgres;

import io.github.lightrag.storage.DocumentGraphStateSupport;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStorageAdapter;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private final VectorStore sharedVectorStore;

    public PostgresVectorStorageAdapter(PostgresStorageProvider postgresProvider) {
        this.postgresProvider = Objects.requireNonNull(postgresProvider, "postgresProvider");
        this.sharedVectorStore = null;
    }

    /**
     * Family-mode adapter backed by a caller-owned store: since family writes already go through the
     * relational transaction, the store carries the vector rows and this adapter only snapshots them.
     */
    PostgresVectorStorageAdapter(VectorStore sharedVectorStore) {
        this.postgresProvider = null;
        this.sharedVectorStore = Objects.requireNonNull(sharedVectorStore, "sharedVectorStore");
    }

    @Override
    public VectorStore vectorStore() {
        return sharedVectorStore != null ? sharedVectorStore : postgresProvider.vectorStore();
    }

    @Override
    public VectorSnapshot captureSnapshot() {
        var namespaces = new LinkedHashMap<String, List<VectorStore.VectorRecord>>();
        var vectorStore = vectorStore();
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
        if (sharedVectorStore != null) {
            restoreSharedSnapshot(source);
            return;
        }
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

    private void restoreSharedSnapshot(VectorSnapshot source) {
        var namespaces = new LinkedHashSet<String>(DEFAULT_NAMESPACES);
        namespaces.addAll(source.namespaces().keySet());
        for (var namespace : namespaces) {
            sharedVectorStore.deleteNamespace(namespace);
            var records = source.namespaces().getOrDefault(namespace, List.of());
            if (!records.isEmpty()) {
                sharedVectorStore.saveAll(namespace, records);
            }
        }
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
