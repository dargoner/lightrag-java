package io.github.lightrag.storage;

import java.util.Optional;

public interface RelationalStorageAdapter extends AutoCloseable {
    DocumentStore documentStore();

    ChunkStore chunkStore();

    DocumentStatusStore documentStatusStore();

    TaskStore taskStore();

    TaskStageStore taskStageStore();

    default TaskDocumentStore taskDocumentStore() {
        throw new UnsupportedOperationException("taskDocumentStore is not available in this relational adapter");
    }

    default LlmCacheStore llmCacheStore() {
        throw new UnsupportedOperationException("llmCacheStore is not available in this relational adapter");
    }

    SnapshotStore snapshotStore();

    default DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        throw new UnsupportedOperationException("documentGraphSnapshotStore is not available in this relational adapter");
    }

    default DocumentGraphJournalStore documentGraphJournalStore() {
        throw new UnsupportedOperationException("documentGraphJournalStore is not available in this relational adapter");
    }

    SnapshotStore.Snapshot captureSnapshot();

    void restore(SnapshotStore.Snapshot snapshot);

    default SnapshotStore.Snapshot toRelationalRestoreSnapshot(SnapshotStore.Snapshot snapshot) {
        return new SnapshotStore.Snapshot(
            snapshot.documents(),
            snapshot.chunks(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            snapshot.documentStatuses(),
            snapshot.documentGraphSnapshots(),
            snapshot.chunkGraphSnapshots(),
            snapshot.documentGraphJournals(),
            snapshot.chunkGraphJournals()
        );
    }

    /**
     * Executes {@code operation} inside a single database transaction.
     *
     * <p><b>Contract ({@link StorageCoordinator} relies on it; overrides must honor it)</b>:</p>
     * <ul>
     *   <li>When {@code operation} throws, <b>every</b> write performed by this transaction must be rolled back,
     *       and the rollback must complete before {@code writeInTransaction} returns or throws — callers capture no
     *       pre-image for the relational side;</li>
     *   <li>A commit the database rejects as failed must be rolled back; an implementation must never end up
     *       partially committed. An <b>unknown commit outcome</b> (for example the connection drops after the
     *       commit request was sent) is outside this contract: the implementation must surface it as an exception
     *       (never swallow it). The relational side may then already be committed while the caller still compensates
     *       graph/vector, leaving a relational-committed / graph-and-vector-rolled-back residue window. The
     *       existence of that window and the target PostgreSQL version's actual behavior are a pre-release
     *       verification item; this contract does not claim to eliminate it;</li>
     *   <li>This method may retry internally (as {@code PostgresRetrySupport} does), but only for transient errors
     *       that mean the database explicitly rejected or rolled back the transaction (the current implementation
     *       matches {@code SQLTransactionRollbackException} and SQL states {@code 40001}/{@code 40P01}/{@code 55P03}/
     *       {@code 57014}). <b>Never retry an unknown commit outcome</b> — a retry may duplicate the commit. A
     *       retry must reuse the same transaction semantics: writes from a previous attempt must not be visible to
     *       the next attempt.</li>
     * </ul>
     *
     * <p>In short: compensating a failed relational write is the transaction's own responsibility, not
     * {@link StorageCoordinator}'s.</p>
     */
    <T> T writeInTransaction(RelationalWriteOperation<T> operation);

    interface RelationalStorageView {
        DocumentStore documentStore();

        ChunkStore chunkStore();

        DocumentStatusStore documentStatusStore();

        default DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            throw new UnsupportedOperationException("documentGraphSnapshotStore is not available in relational transaction view");
        }

        default DocumentGraphJournalStore documentGraphJournalStore() {
            throw new UnsupportedOperationException("documentGraphJournalStore is not available in relational transaction view");
        }

        default TaskStore taskStore() {
            throw new UnsupportedOperationException("taskStore is not available in relational transaction view");
        }

        default TaskStageStore taskStageStore() {
            throw new UnsupportedOperationException("taskStageStore is not available in relational transaction view");
        }

        default TaskDocumentStore taskDocumentStore() {
            throw new UnsupportedOperationException("taskDocumentStore is not available in relational transaction view");
        }

        default Optional<GraphStore> transactionalGraphStore() {
            return Optional.empty();
        }

        default Optional<VectorStore> transactionalVectorStore() {
            return Optional.empty();
        }
    }

    @FunctionalInterface
    interface RelationalWriteOperation<T> {
        T execute(RelationalStorageView storage);
    }

    @Override
    default void close() {
    }
}
