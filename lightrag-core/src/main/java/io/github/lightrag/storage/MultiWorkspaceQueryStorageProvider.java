package io.github.lightrag.storage;

import java.util.Objects;

/**
 * Read-only store set that answers query-path reads across a set of workspaces in one call.
 *
 * <p>The query stores ({@code chunkStore}/{@code graphStore}/{@code vectorStore}) are workspace-set
 * implementations that batch their reads with an IN filter; {@code llmCacheStore} is a
 * {@link NoopLlmCacheStore} because cache hit attribution cannot be addressed across a workspace
 * set and a miss only costs a recomputation. Every remaining store is delegated as-is, primarily so
 * the builder's eager validation resolves against a real store set; indexing and write entry points
 * must not be used with this provider and fail loudly instead of writing into an arbitrary
 * workspace.</p>
 */
public final class MultiWorkspaceQueryStorageProvider implements AtomicStorageProvider {
    private final StorageProvider delegate;
    private final ChunkStore chunkStore;
    private final GraphStore graphStore;
    private final VectorStore vectorStore;
    private final LlmCacheStore llmCacheStore = new NoopLlmCacheStore();

    public MultiWorkspaceQueryStorageProvider(
        StorageProvider delegate,
        ChunkStore chunkStore,
        GraphStore graphStore,
        VectorStore vectorStore
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.chunkStore = Objects.requireNonNull(chunkStore, "chunkStore");
        this.graphStore = Objects.requireNonNull(graphStore, "graphStore");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    @Override
    public DocumentStore documentStore() {
        return delegate.documentStore();
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
        return delegate.documentStatusStore();
    }

    @Override
    public TaskStore taskStore() {
        return delegate.taskStore();
    }

    @Override
    public TaskStageStore taskStageStore() {
        return delegate.taskStageStore();
    }

    @Override
    public TaskDocumentStore taskDocumentStore() {
        return delegate.taskDocumentStore();
    }

    @Override
    public LlmCacheStore llmCacheStore() {
        return llmCacheStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return delegate.snapshotStore();
    }

    @Override
    public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        return delegate.documentGraphSnapshotStore();
    }

    @Override
    public DocumentGraphJournalStore documentGraphJournalStore() {
        return delegate.documentGraphJournalStore();
    }

    @Override
    public EmbeddingSpaceStore embeddingSpaceStore() {
        return delegate.embeddingSpaceStore();
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        throw new UnsupportedOperationException(
            "workspace-set query storage is read-only; writeAtomically is not supported");
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        throw new UnsupportedOperationException(
            "workspace-set query storage is read-only; restore is not supported");
    }
}
