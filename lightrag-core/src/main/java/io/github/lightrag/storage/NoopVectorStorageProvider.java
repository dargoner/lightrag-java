package io.github.lightrag.storage;

import java.util.Objects;

/**
 * Decorates an {@link AtomicStorageProvider} so every vector view resolves to a {@link NoopVectorStore}:
 * pipelines skip embedding work entirely and query-time vector search returns no hits. All non-vector
 * stores keep delegating to the wrapped provider.
 */
public final class NoopVectorStorageProvider implements AtomicStorageProvider {
    private final AtomicStorageProvider delegate;
    private final VectorStore noopVectorStore = new NoopVectorStore();

    public NoopVectorStorageProvider(AtomicStorageProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public DocumentStore documentStore() {
        return delegate.documentStore();
    }

    @Override
    public ChunkStore chunkStore() {
        return delegate.chunkStore();
    }

    @Override
    public GraphStore graphStore() {
        return delegate.graphStore();
    }

    @Override
    public VectorStore vectorStore() {
        return noopVectorStore;
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
        return delegate.llmCacheStore();
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
        Objects.requireNonNull(operation, "operation");
        return delegate.writeAtomically(view -> operation.execute(new NoopVectorView(view, noopVectorStore)));
    }

    @Override
    public void restore(SnapshotStore.Snapshot snapshot) {
        delegate.restore(snapshot);
    }

    private record NoopVectorView(AtomicStorageView delegate, VectorStore noopVectorStore) implements AtomicStorageView {
        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
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
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return noopVectorStore;
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public EmbeddingSpaceStore embeddingSpaceStore() {
            return delegate.embeddingSpaceStore();
        }
    }
}
