package io.github.lightragjava.indexing;

import io.github.lightragjava.api.DocumentStatus;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.types.Document;
import io.github.lightragjava.types.RawDocumentSource;
import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.Relation;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class IndexingPipeline {
    private final AtomicStorageProvider storageProvider;
    private final DocumentIngestor documentIngestor;
    private final KnowledgeExtractor knowledgeExtractor;
    private final GraphAssembler graphAssembler;
    private final EmbeddingBatcher embeddingBatcher;
    private final DocumentParsingOrchestrator documentParsingOrchestrator;
    private final Path snapshotPath;
    private final int maxParallelInsert;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final Object storageMutationMonitor = new Object();

    public IndexingPipeline(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath,
        Chunker chunker,
        int embeddingBatchSize,
        int maxParallelInsert,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.snapshotPath = snapshotPath;
        var effectiveEmbeddingBatchSize = embeddingBatchSize <= 0 ? Integer.MAX_VALUE : embeddingBatchSize;
        this.maxParallelInsert = Math.max(1, maxParallelInsert);
        this.entityExtractMaxGleaning = Math.max(0, entityExtractMaxGleaning);
        this.maxExtractInputTokens = maxExtractInputTokens <= 0
            ? KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS
            : maxExtractInputTokens;
        this.embeddingBatcher = new EmbeddingBatcher(Objects.requireNonNull(embeddingModel, "embeddingModel"), effectiveEmbeddingBatchSize);
        var effectiveChunker = chunker == null
            ? new FixedWindowChunker(FixedWindowChunker.DEFAULT_WINDOW_SIZE, FixedWindowChunker.DEFAULT_OVERLAP)
            : chunker;
        this.documentIngestor = new DocumentIngestor(
            storageProvider,
            effectiveChunker,
            chunkPreparationStrategy(effectiveChunker, embeddingSemanticMergeEnabled, embeddingSemanticMergeThreshold)
        );
        this.knowledgeExtractor = new KnowledgeExtractor(
            Objects.requireNonNull(chatModel, "chatModel"),
            this.entityExtractMaxGleaning,
            this.maxExtractInputTokens
        );
        this.graphAssembler = new GraphAssembler();
        this.documentParsingOrchestrator = new DocumentParsingOrchestrator(new PlainTextParsingProvider());
    }

    public IndexingPipeline(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath
    ) {
        this(
            chatModel,
            embeddingModel,
            storageProvider,
            snapshotPath,
            null,
            Integer.MAX_VALUE,
            1,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            false,
            0.80d
        );
    }

    public void ingest(List<Document> documents) {
        var sources = List.copyOf(Objects.requireNonNull(documents, "documents"));
        if (sources.size() <= 1 || maxParallelInsert <= 1) {
            for (var document : sources) {
                ingestSequentially(document);
            }
            return;
        }
        ingestConcurrently(sources);
    }

    public void ingestSources(List<RawDocumentSource> sources, io.github.lightragjava.api.DocumentIngestOptions options) {
        var rawSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        Objects.requireNonNull(options, "options");
        var documents = rawSources.stream()
            .map(source -> toDocument(documentParsingOrchestrator.parse(source, options)))
            .toList();
        ingest(documents);
    }

    private void ingestSequentially(Document document) {
        var source = Objects.requireNonNull(document, "document");
        saveStatus(processingStatus(source.id()));
        try {
            commitComputedIngest(computeDocument(source));
            persistSnapshotIfConfigured();
        } catch (RuntimeException | Error failure) {
            saveFailureStatus(source.id(), failure);
            persistSnapshotIfConfigured();
            throw failure;
        }
    }

    private void ingestConcurrently(List<Document> documents) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxParallelInsert, documents.size()));
        var completionService = new ExecutorCompletionService<ComputedIngest>(executor);
        var pendingTasks = new LinkedHashMap<Future<ComputedIngest>, Document>();
        try {
            for (var document : documents) {
                saveStatus(processingStatus(document.id()));
                pendingTasks.put(completionService.submit(() -> computeDocument(document)), document);
            }
            while (!pendingTasks.isEmpty()) {
                var completed = completionService.take();
                var source = pendingTasks.remove(completed);
                try {
                    commitComputedIngest(completed.get());
                    persistSnapshotIfConfigured();
                } catch (ExecutionException exception) {
                    cancelPending(pendingTasks.keySet());
                    saveFailureStatus(source.id(), exception.getCause());
                    markPendingDocumentsFailed(pendingTasks.values(), "ingest aborted because another document failed");
                    persistSnapshotIfConfigured();
                    rethrowTaskFailure(exception.getCause());
                }
            }
        } catch (InterruptedException exception) {
            cancelPending(pendingTasks.keySet());
            Thread.currentThread().interrupt();
            markPendingDocumentsFailed(pendingTasks.values(), "document ingest interrupted");
            persistSnapshotIfConfigured();
            throw new RuntimeException("document ingest interrupted", exception);
        } finally {
            shutdownExecutor(executor);
        }
    }

    private void markPendingDocumentsFailed(Collection<Document> documents, String errorMessage) {
        for (var document : documents) {
            saveStatus(new DocumentStatusStore.StatusRecord(
                document.id(),
                DocumentStatus.FAILED,
                "",
                errorMessage
            ));
        }
    }

    private ComputedIngest computeDocument(Document document) {
        var source = Objects.requireNonNull(document, "document");
        var prepared = documentIngestor.prepare(List.of(source));
        var chunks = prepared.chunks();
        var chunkVectors = chunkVectors(chunks);
        var graph = graphAssembler.assemble(chunks.stream()
            .map(chunk -> new GraphAssembler.ChunkExtraction(chunk.id(), knowledgeExtractor.extract(chunk)))
            .toList());
        var entityVectors = entityVectors(graph.entities());
        var relationVectors = relationVectors(graph.relations());
        return new ComputedIngest(source, prepared, chunkVectors, graph, entityVectors, relationVectors);
    }

    private static void cancelPending(Collection<? extends Future<?>> futures) {
        for (var future : futures) {
            future.cancel(true);
        }
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void rethrowTaskFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("document ingest failed", failure);
    }

    private void commitComputedIngest(ComputedIngest computed) {
        try {
            synchronized (storageMutationMonitor) {
                storageProvider.writeAtomically(storage -> {
                    documentIngestor.persist(computed.prepared(), storage);
                    saveVectors(StorageSnapshots.CHUNK_NAMESPACE, computed.chunkVectors(), storage.vectorStore());
                    saveGraph(computed.graph().entities(), computed.graph().relations(), storage);
                    saveVectors(StorageSnapshots.ENTITY_NAMESPACE, computed.entityVectors(), storage.vectorStore());
                    saveVectors(StorageSnapshots.RELATION_NAMESPACE, computed.relationVectors(), storage.vectorStore());
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        computed.source().id(),
                        DocumentStatus.PROCESSED,
                        "processed %d chunks".formatted(computed.prepared().chunks().size()),
                        null
                    ));
                    return null;
                });
            }
        } catch (RuntimeException | Error failure) {
            saveFailureStatus(computed.source().id(), failure);
            throw failure;
        }
    }

    private void saveFailureStatus(String documentId, Throwable failure) {
        saveStatus(new DocumentStatusStore.StatusRecord(
            documentId,
            DocumentStatus.FAILED,
            "",
            failure == null ? null : failure.getMessage()
        ));
    }

    private void saveStatus(DocumentStatusStore.StatusRecord statusRecord) {
        synchronized (storageMutationMonitor) {
            storageProvider.writeAtomically(storage -> {
                storage.documentStatusStore().save(statusRecord);
                return null;
            });
        }
    }

    private void saveGraph(List<Entity> entities, List<Relation> relations, AtomicStorageProvider.AtomicStorageView storage) {
        for (var entity : entities) {
            var mergedEntity = storage.graphStore().loadEntity(entity.id())
                .map(existing -> mergeEntity(existing, entity))
                .orElseGet(() -> toEntityRecord(entity));
            storage.graphStore().saveEntity(mergedEntity);
        }

        for (var relation : relations) {
            var mergedRelation = storage.graphStore().loadRelation(relation.id())
                .map(existing -> mergeRelation(existing, relation))
                .orElseGet(() -> toRelationRecord(relation));
            storage.graphStore().saveRelation(mergedRelation);
        }
    }

    private void saveVectors(String namespace, List<VectorStore.VectorRecord> vectors, VectorStore vectorStore) {
        if (vectors.isEmpty()) {
            return;
        }
        vectorStore.saveAll(namespace, vectors);
    }

    private void persistSnapshotIfConfigured() {
        synchronized (storageMutationMonitor) {
            StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
        }
    }

    private static DocumentStatusStore.StatusRecord processingStatus(String documentId) {
        return new DocumentStatusStore.StatusRecord(documentId, DocumentStatus.PROCESSING, "", null);
    }

    private static Document toDocument(ParsedDocument parsed) {
        return new Document(
            parsed.documentId(),
            parsed.title(),
            parsed.plainText(),
            parsed.metadata()
        );
    }

    private static List<VectorStore.VectorRecord> toVectorRecords(List<String> ids, List<List<Double>> embeddings) {
        if (ids.size() != embeddings.size()) {
            throw new IllegalStateException("embedding count does not match indexed item count");
        }

        return java.util.stream.IntStream.range(0, ids.size())
            .mapToObj(index -> new VectorStore.VectorRecord(ids.get(index), embeddings.get(index)))
            .toList();
    }

    private List<VectorStore.VectorRecord> chunkVectors(List<io.github.lightragjava.types.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingBatcher.embedAll(chunks.stream().map(io.github.lightragjava.types.Chunk::text).toList());
        return toVectorRecords(chunks.stream().map(io.github.lightragjava.types.Chunk::id).toList(), embeddings);
    }

    List<VectorStore.VectorRecord> entityVectors(List<Entity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingBatcher.embedAll(entities.stream().map(IndexingPipeline::entitySummary).toList());
        return toVectorRecords(entities.stream().map(Entity::id).toList(), embeddings);
    }

    List<VectorStore.VectorRecord> relationVectors(List<Relation> relations) {
        if (relations.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingBatcher.embedAll(relations.stream().map(IndexingPipeline::relationSummary).toList());
        return toVectorRecords(relations.stream().map(Relation::id).toList(), embeddings);
    }

    private DocumentChunkPreparationStrategy chunkPreparationStrategy(
        Chunker chunker,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold
    ) {
        if (!embeddingSemanticMergeEnabled) {
            return new DefaultChunkPreparationStrategy();
        }
        if (!(chunker instanceof SmartChunker)) {
            throw new IllegalStateException("embedding semantic merge requires SmartChunker");
        }
        return new SmartChunkerEmbeddingPreparationStrategy(
            new SemanticChunkRefiner(),
            new EmbeddingChunkSimilarityScorer(embeddingBatcher),
            embeddingSemanticMergeThreshold
        );
    }

    private static GraphStore.EntityRecord mergeEntity(GraphStore.EntityRecord existing, Entity incoming) {
        return new GraphStore.EntityRecord(
            existing.id(),
            existing.name(),
            existing.type().isEmpty() ? incoming.type() : existing.type(),
            existing.description().isEmpty() ? incoming.description() : existing.description(),
            union(existing.aliases(), incoming.aliases()),
            union(existing.sourceChunkIds(), incoming.sourceChunkIds())
        );
    }

    private static GraphStore.RelationRecord mergeRelation(GraphStore.RelationRecord existing, Relation incoming) {
        return new GraphStore.RelationRecord(
            existing.id(),
            existing.sourceEntityId(),
            existing.targetEntityId(),
            existing.type(),
            existing.description().isEmpty() ? incoming.description() : existing.description(),
            Math.max(existing.weight(), incoming.weight()),
            union(existing.sourceChunkIds(), incoming.sourceChunkIds())
        );
    }

    private static GraphStore.EntityRecord toEntityRecord(Entity entity) {
        return new GraphStore.EntityRecord(
            entity.id(),
            entity.name(),
            entity.type(),
            entity.description(),
            entity.aliases(),
            entity.sourceChunkIds()
        );
    }

    private static GraphStore.RelationRecord toRelationRecord(Relation relation) {
        return new GraphStore.RelationRecord(
            relation.id(),
            relation.sourceEntityId(),
            relation.targetEntityId(),
            relation.type(),
            relation.description(),
            relation.weight(),
            relation.sourceChunkIds()
        );
    }

    private static String entitySummary(Entity entity) {
        return "%s\n%s\n%s\n%s".formatted(
            entity.name(),
            entity.type(),
            entity.description(),
            String.join(", ", entity.aliases())
        );
    }

    private static String relationSummary(Relation relation) {
        return "%s\n%s\n%s\n%s".formatted(
            relation.sourceEntityId(),
            relation.type(),
            relation.targetEntityId(),
            relation.description()
        );
    }

    private static List<String> union(List<String> left, List<String> right) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private record ComputedIngest(
        Document source,
        DocumentIngestor.PreparedIngest prepared,
        List<VectorStore.VectorRecord> chunkVectors,
        GraphAssembler.Graph graph,
        List<VectorStore.VectorRecord> entityVectors,
        List<VectorStore.VectorRecord> relationVectors
    ) {
    }
}
