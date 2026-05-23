package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.LlmCacheStore;
import io.github.lightrag.storage.OneShotRetrievalStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskDocumentStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class ArcadeStorageProvider implements AtomicStorageProvider, OneShotRetrievalStore, AutoCloseable {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ArcadeDbClient client;
    private final SnapshotStore snapshotStore;
    private final ArcadeDocumentStore documentStore;
    private final ArcadeChunkStore chunkStore;
    private final ArcadeGraphStore graphStore;
    private final ArcadeVectorStore vectorStore;
    private final ArcadeOneShotRetrievalStore oneShotRetrievalStore;
    private final ArcadeDocumentStatusStore documentStatusStore;
    private final ArcadeTaskStore taskStore;
    private final ArcadeTaskStageStore taskStageStore;
    private final ArcadeTaskDocumentStore taskDocumentStore;
    private final ArcadeLlmCacheStore llmCacheStore;
    private final ArcadeDocumentGraphSnapshotStore documentGraphSnapshotStore;
    private final ArcadeDocumentGraphJournalStore documentGraphJournalStore;

    public ArcadeStorageProvider(ArcadeDbConfig config, SnapshotStore snapshotStore, String workspaceId) {
        this(new ArcadeDbClient(config), config, snapshotStore, workspaceId, true);
    }

    ArcadeStorageProvider(
        ArcadeDbClient client,
        ArcadeDbConfig config,
        SnapshotStore snapshotStore,
        String workspaceId,
        boolean bootstrap
    ) {
        this.client = Objects.requireNonNull(client, "client");
        var resolvedConfig = Objects.requireNonNull(config, "config");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        var resolvedWorkspace = Objects.requireNonNull(workspaceId, "workspaceId");
        if (bootstrap && resolvedConfig.initSchema()) {
            new ArcadeSchemaManager(client, resolvedConfig).bootstrap();
        }
        this.documentStore = new ArcadeDocumentStore(client, resolvedWorkspace);
        this.chunkStore = new ArcadeChunkStore(client, resolvedWorkspace);
        this.graphStore = new ArcadeGraphStore(client, resolvedWorkspace);
        this.vectorStore = new ArcadeVectorStore(client, resolvedWorkspace, resolvedConfig.vectorDimensions());
        this.oneShotRetrievalStore = new ArcadeOneShotRetrievalStore(client, resolvedWorkspace);
        this.documentStatusStore = new ArcadeDocumentStatusStore(client, resolvedWorkspace);
        this.taskStore = new ArcadeTaskStore(client, resolvedWorkspace);
        this.taskStageStore = new ArcadeTaskStageStore(client, resolvedWorkspace);
        this.taskDocumentStore = new ArcadeTaskDocumentStore(client, resolvedWorkspace);
        this.llmCacheStore = new ArcadeLlmCacheStore(client, resolvedWorkspace);
        this.documentGraphSnapshotStore = new ArcadeDocumentGraphSnapshotStore(client, resolvedWorkspace);
        this.documentGraphJournalStore = new ArcadeDocumentGraphJournalStore(client, resolvedWorkspace);
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
    public TaskStore taskStore() {
        return taskStore;
    }

    @Override
    public TaskStageStore taskStageStore() {
        return taskStageStore;
    }

    @Override
    public TaskDocumentStore taskDocumentStore() {
        return taskDocumentStore;
    }

    @Override
    public LlmCacheStore llmCacheStore() {
        return llmCacheStore;
    }

    @Override
    public SnapshotStore snapshotStore() {
        return snapshotStore;
    }

    @Override
    public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
        return documentGraphSnapshotStore;
    }

    @Override
    public DocumentGraphJournalStore documentGraphJournalStore() {
        return documentGraphJournalStore;
    }

    @Override
    public LocalRetrievalResult retrieveLocal(List<VectorStore.VectorMatch> entityMatches) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return oneShotRetrievalStore.retrieveLocal(entityMatches);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public GlobalRetrievalResult retrieveGlobal(List<VectorStore.VectorMatch> relationMatches) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return oneShotRetrievalStore.retrieveGlobal(relationMatches);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public DirectChunkRetrievalResult retrieveChunks(List<VectorStore.VectorMatch> chunkMatches) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return oneShotRetrievalStore.retrieveChunks(chunkMatches);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean supportsMixRetrieval() {
        return oneShotRetrievalStore.supportsMixRetrieval();
    }

    @Override
    public MixRetrievalResult retrieveMix(
        List<VectorStore.VectorMatch> entityMatches,
        List<VectorStore.VectorMatch> relationMatches,
        List<VectorStore.VectorMatch> chunkMatches
    ) {
        var readLock = lock.readLock();
        readLock.lock();
        try {
            return oneShotRetrievalStore.retrieveMix(entityMatches, relationMatches, chunkMatches);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <T> T writeAtomically(AtomicOperation<T> operation) {
        var writeLock = lock.writeLock();
        writeLock.lock();
        try {
            return Objects.requireNonNull(operation, "operation").execute(new AtomicView(
                documentStore,
                chunkStore,
                documentGraphSnapshotStore,
                documentGraphJournalStore,
                graphStore,
                vectorStore,
                documentStatusStore
            ));
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

    private void restoreStores(SnapshotStore.Snapshot snapshot) {
        truncateAll();
        for (var document : snapshot.documents()) {
            documentStore.save(document);
        }
        for (var chunk : snapshot.chunks()) {
            chunkStore.save(chunk);
        }
        for (var entity : snapshot.entities()) {
            graphStore.saveEntity(entity);
        }
        for (var relation : snapshot.relations()) {
            graphStore.saveRelation(relation);
        }
        for (var entry : snapshot.vectors().entrySet()) {
            vectorStore.saveAll(entry.getKey(), entry.getValue());
        }
        for (var status : snapshot.documentStatuses()) {
            documentStatusStore.save(status);
        }
        for (var documentSnapshot : snapshot.documentGraphSnapshots()) {
            documentGraphSnapshotStore.saveDocument(documentSnapshot);
        }
        var chunksByDocument = new LinkedHashMap<String, java.util.ArrayList<DocumentGraphSnapshotStore.ChunkGraphSnapshot>>();
        for (var chunkSnapshot : snapshot.chunkGraphSnapshots()) {
            chunksByDocument.computeIfAbsent(chunkSnapshot.documentId(), ignored -> new java.util.ArrayList<>()).add(chunkSnapshot);
        }
        for (var entry : chunksByDocument.entrySet()) {
            documentGraphSnapshotStore.saveChunks(entry.getKey(), entry.getValue());
        }
        for (var journal : snapshot.documentGraphJournals()) {
            documentGraphJournalStore.appendDocument(journal);
        }
        var chunkJournalsByDocument = new LinkedHashMap<String, java.util.ArrayList<DocumentGraphJournalStore.ChunkGraphJournal>>();
        for (var chunkJournal : snapshot.chunkGraphJournals()) {
            chunkJournalsByDocument.computeIfAbsent(chunkJournal.documentId(), ignored -> new java.util.ArrayList<>()).add(chunkJournal);
        }
        for (var entry : chunkJournalsByDocument.entrySet()) {
            documentGraphJournalStore.appendChunks(entry.getKey(), entry.getValue());
        }
    }

    private void truncateAll() {
        taskDocumentStore.deleteAll();
        taskStageStore.deleteAll();
        taskStore.deleteAll();
        llmCacheStore.deleteAll();
        documentGraphJournalStore.deleteAll();
        documentGraphSnapshotStore.deleteAll();
        documentStatusStore.deleteAll();
        vectorStore.deleteAll();
        graphStore.deleteAll();
        chunkStore.deleteAll();
        documentStore.deleteAll();
    }

    @Override
    public void close() {
        client.close();
    }

    private record AtomicView(
        DocumentStore documentStore,
        ChunkStore chunkStore,
        DocumentGraphSnapshotStore documentGraphSnapshotStore,
        DocumentGraphJournalStore documentGraphJournalStore,
        GraphStore graphStore,
        VectorStore vectorStore,
        DocumentStatusStore documentStatusStore
    ) implements AtomicStorageView {
    }
}
