package io.github.lightrag.indexing;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.SnapshotSource;
import io.github.lightrag.api.SnapshotStatus;
import io.github.lightrag.indexing.refinement.DefaultAttributionResolver;
import io.github.lightrag.indexing.refinement.DefaultExtractionGapDetector;
import io.github.lightrag.indexing.refinement.DefaultExtractionMergePolicy;
import io.github.lightrag.indexing.refinement.DefaultRefinementWindowResolver;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
import io.github.lightrag.indexing.refinement.ExtractionRefinementPipeline;
import io.github.lightrag.indexing.refinement.PrimaryChunkExtraction;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Document;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.RawDocumentSource;
import io.github.lightrag.types.Relation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
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
    private static final Logger log = LoggerFactory.getLogger(IndexingPipeline.class);
    private final AtomicStorageProvider storageProvider;
    private final DocumentIngestor documentIngestor;
    private final KnowledgeExtractor knowledgeExtractor;
    // Reserved for upcoming summarization stages so capability routing stays centralized.
    private final ChatModel summaryModel;
    private final GraphAssembler graphAssembler;
    private final EmbeddingBatcher embeddingBatcher;
    private final ExtractionRefinementPipeline extractionRefinementPipeline;
    private final DocumentParsingOrchestrator documentParsingOrchestrator;
    private final Path snapshotPath;
    private final int maxParallelInsert;
    private final int entityExtractMaxGleaning;
    private final int maxExtractInputTokens;
    private final String entityExtractionLanguage;
    private final List<String> entityTypes;
    private final ExtractionRefinementOptions extractionRefinementOptions;
    private final IndexingProgressListener progressListener;
    private final Object storageMutationMonitor = new Object();

    public IndexingPipeline(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        int embeddingBatchSize,
        int maxParallelInsert,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold,
        ExtractionRefinementOptions extractionRefinementOptions
    ) {
        this(
            chatModel,
            chatModel,
            embeddingModel,
            storageProvider,
            snapshotPath,
            chunker,
            documentParsingOrchestrator,
            embeddingBatchSize,
            maxParallelInsert,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            entityExtractionLanguage,
            entityTypes,
            embeddingSemanticMergeEnabled,
            embeddingSemanticMergeThreshold,
            extractionRefinementOptions,
            IndexingProgressListener.noop()
        );
    }

    public IndexingPipeline(
        ChatModel extractionModel,
        ChatModel summaryModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        int embeddingBatchSize,
        int maxParallelInsert,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold,
        ExtractionRefinementOptions extractionRefinementOptions
    ) {
        this(
            extractionModel,
            summaryModel,
            embeddingModel,
            storageProvider,
            snapshotPath,
            chunker,
            documentParsingOrchestrator,
            embeddingBatchSize,
            maxParallelInsert,
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            entityExtractionLanguage,
            entityTypes,
            embeddingSemanticMergeEnabled,
            embeddingSemanticMergeThreshold,
            extractionRefinementOptions,
            IndexingProgressListener.noop()
        );
    }

    public IndexingPipeline(
        ChatModel extractionModel,
        ChatModel summaryModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath,
        Chunker chunker,
        DocumentParsingOrchestrator documentParsingOrchestrator,
        int embeddingBatchSize,
        int maxParallelInsert,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes,
        boolean embeddingSemanticMergeEnabled,
        double embeddingSemanticMergeThreshold,
        ExtractionRefinementOptions extractionRefinementOptions,
        IndexingProgressListener progressListener
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.snapshotPath = snapshotPath;
        var effectiveEmbeddingBatchSize = embeddingBatchSize <= 0 ? Integer.MAX_VALUE : embeddingBatchSize;
        this.maxParallelInsert = Math.max(1, maxParallelInsert);
        this.entityExtractMaxGleaning = Math.max(0, entityExtractMaxGleaning);
        this.maxExtractInputTokens = maxExtractInputTokens <= 0
            ? KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS
            : maxExtractInputTokens;
        this.entityExtractionLanguage = entityExtractionLanguage == null || entityExtractionLanguage.isBlank()
            ? KnowledgeExtractor.DEFAULT_LANGUAGE
            : entityExtractionLanguage.strip();
        this.entityTypes = entityTypes == null || entityTypes.isEmpty()
            ? KnowledgeExtractor.DEFAULT_ENTITY_TYPES
            : List.copyOf(entityTypes);
        this.extractionRefinementOptions = extractionRefinementOptions == null
            ? ExtractionRefinementOptions.disabled()
            : extractionRefinementOptions;
        this.embeddingBatcher = new EmbeddingBatcher(Objects.requireNonNull(embeddingModel, "embeddingModel"), effectiveEmbeddingBatchSize);
        var effectiveChunker = chunker == null
            ? new FixedWindowChunker(FixedWindowChunker.DEFAULT_WINDOW_SIZE, FixedWindowChunker.DEFAULT_OVERLAP)
            : chunker;
        this.documentIngestor = new DocumentIngestor(
            storageProvider,
            effectiveChunker,
            chunkPreparationStrategy(effectiveChunker, embeddingSemanticMergeEnabled, embeddingSemanticMergeThreshold),
            new ChunkingOrchestrator()
        );
        this.summaryModel = Objects.requireNonNull(summaryModel, "summaryModel");
        this.knowledgeExtractor = new KnowledgeExtractor(
            Objects.requireNonNull(extractionModel, "extractionModel"),
            this.entityExtractMaxGleaning,
            this.maxExtractInputTokens,
            this.entityExtractionLanguage,
            this.entityTypes,
            this.extractionRefinementOptions.allowDeterministicAttributionFallback()
        );
        this.graphAssembler = new GraphAssembler();
        this.extractionRefinementPipeline = new ExtractionRefinementPipeline(
            this.extractionRefinementOptions,
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, ignored) -> this.knowledgeExtractor.extractWindow(window),
            new DefaultAttributionResolver(this.extractionRefinementOptions.allowDeterministicAttributionFallback()),
            new DefaultExtractionMergePolicy()
        );
        this.documentParsingOrchestrator = documentParsingOrchestrator == null
            ? new DocumentParsingOrchestrator(new PlainTextParsingProvider())
            : documentParsingOrchestrator;
        this.progressListener = progressListener == null ? IndexingProgressListener.noop() : progressListener;
    }

    public IndexingPipeline(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath
    ) {
        this(
            chatModel,
            chatModel,
            embeddingModel,
            storageProvider,
            snapshotPath
        );
    }

    public IndexingPipeline(
        ChatModel extractionModel,
        ChatModel summaryModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath
    ) {
        this(
            extractionModel,
            summaryModel,
            embeddingModel,
            storageProvider,
            snapshotPath,
            null,
            null,
            Integer.MAX_VALUE,
            1,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            false,
            0.80d,
            ExtractionRefinementOptions.disabled()
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

    public void ingestSources(List<RawDocumentSource> sources, io.github.lightrag.api.DocumentIngestOptions options) {
        var rawSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        var resolvedOptions = Objects.requireNonNull(options, "options");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.PARSING, "parsing source documents");
        for (var source : rawSources) {
            saveStatus(processingStatus(source.sourceId()));
        }
        var parsedDocuments = new java.util.ArrayList<ParsedDocument>(rawSources.size());
        for (int index = 0; index < rawSources.size(); index++) {
            var source = rawSources.get(index);
            try {
                parsedDocuments.add(documentParsingOrchestrator.parse(source, resolvedOptions));
            } catch (RuntimeException | Error failure) {
                saveFailureStatus(source.sourceId(), failure);
                markPendingRawSourcesFailed(rawSources.subList(0, index), "document parsing aborted because another document failed");
                markPendingRawSourcesFailed(
                    rawSources.subList(index + 1, rawSources.size()),
                    "document parsing aborted because another document failed"
                );
                persistSnapshotIfConfigured();
                throw failure;
            }
        }
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.PARSING, "parsed %d source documents".formatted(parsedDocuments.size()));
        if (parsedDocuments.size() <= 1 || maxParallelInsert <= 1) {
            for (var parsed : parsedDocuments) {
                ingestParsedSequentially(parsed, resolvedOptions);
            }
            return;
        }
        ingestParsedConcurrently(parsedDocuments, resolvedOptions);
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

    private void ingestParsedSequentially(ParsedDocument parsedDocument, io.github.lightrag.api.DocumentIngestOptions options) {
        var source = Objects.requireNonNull(parsedDocument, "parsedDocument");
        saveStatus(processingStatus(source.documentId()));
        try {
            commitComputedIngest(computeDocument(source, options));
            persistSnapshotIfConfigured();
        } catch (RuntimeException | Error failure) {
            saveFailureStatus(source.documentId(), failure);
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

    private void ingestParsedConcurrently(
        List<ParsedDocument> parsedDocuments,
        io.github.lightrag.api.DocumentIngestOptions options
    ) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxParallelInsert, parsedDocuments.size()));
        var completionService = new ExecutorCompletionService<ComputedIngest>(executor);
        var pendingTasks = new LinkedHashMap<Future<ComputedIngest>, ParsedDocument>();
        try {
            for (var parsed : parsedDocuments) {
                saveStatus(processingStatus(parsed.documentId()));
                pendingTasks.put(completionService.submit(() -> computeDocument(parsed, options)), parsed);
            }
            while (!pendingTasks.isEmpty()) {
                var completed = completionService.take();
                var source = pendingTasks.remove(completed);
                try {
                    commitComputedIngest(completed.get());
                    persistSnapshotIfConfigured();
                } catch (ExecutionException exception) {
                    cancelPending(pendingTasks.keySet());
                    saveFailureStatus(source.documentId(), exception.getCause());
                    markPendingParsedDocumentsFailed(pendingTasks.values(), "ingest aborted because another document failed");
                    persistSnapshotIfConfigured();
                    rethrowTaskFailure(exception.getCause());
                }
            }
        } catch (InterruptedException exception) {
            cancelPending(pendingTasks.keySet());
            Thread.currentThread().interrupt();
            markPendingParsedDocumentsFailed(pendingTasks.values(), "document ingest interrupted");
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

    private void markPendingParsedDocumentsFailed(Collection<ParsedDocument> documents, String errorMessage) {
        for (var document : documents) {
            saveStatus(new DocumentStatusStore.StatusRecord(
                document.documentId(),
                DocumentStatus.FAILED,
                "",
                errorMessage
            ));
        }
    }

    private void markPendingRawSourcesFailed(Collection<RawDocumentSource> sources, String errorMessage) {
        for (var source : sources) {
            saveStatus(new DocumentStatusStore.StatusRecord(
                source.sourceId(),
                DocumentStatus.FAILED,
                "",
                errorMessage
            ));
        }
    }

    private ComputedIngest computeDocument(Document document) {
        var source = Objects.requireNonNull(document, "document");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.CHUNKING, "chunking document " + source.id());
        var prepared = documentIngestor.prepare(List.of(source));
        progressListener.onStageSucceeded(
            io.github.lightrag.api.TaskStage.CHUNKING,
            "chunked %d chunks for %s".formatted(prepared.chunks().size(), source.id())
        );
        var chunks = prepared.chunks();
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedding chunks for " + source.id());
        var chunkVectors = chunkVectors(chunks);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedded chunk vectors for " + source.id());
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.GRAPH_ASSEMBLY, "assembling graph for " + source.id());
        var refinedExtractions = refineExtractions(chunks);
        var graph = graphAssembler.assemble(refinedExtractions);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.GRAPH_ASSEMBLY, "assembled graph for " + source.id());
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedding graph vectors for " + source.id());
        var entityVectors = entityVectors(graph.entities());
        var relationVectors = relationVectors(graph.relations());
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedded graph vectors for " + source.id());
        return new ComputedIngest(source, prepared, refinedExtractions, chunkVectors, graph, entityVectors, relationVectors);
    }

    private ComputedIngest computeDocument(
        ParsedDocument parsedDocument,
        io.github.lightrag.api.DocumentIngestOptions options
    ) {
        var source = Objects.requireNonNull(parsedDocument, "parsedDocument");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.CHUNKING, "chunking document " + source.documentId());
        var prepared = documentIngestor.prepareParsed(source, Objects.requireNonNull(options, "options"));
        progressListener.onStageSucceeded(
            io.github.lightrag.api.TaskStage.CHUNKING,
            "chunked %d chunks for %s".formatted(prepared.chunks().size(), source.documentId())
        );
        var chunks = prepared.chunks();
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedding chunks for " + source.documentId());
        var chunkVectors = chunkVectors(chunks);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedded chunk vectors for " + source.documentId());
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.GRAPH_ASSEMBLY, "assembling graph for " + source.documentId());
        var refinedExtractions = refineExtractions(chunks);
        var graph = graphAssembler.assemble(refinedExtractions);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.GRAPH_ASSEMBLY, "assembled graph for " + source.documentId());
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedding graph vectors for " + source.documentId());
        var entityVectors = entityVectors(graph.entities());
        var relationVectors = relationVectors(graph.relations());
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_INDEXING, "embedded graph vectors for " + source.documentId());
        return new ComputedIngest(toDocument(source), prepared, refinedExtractions, chunkVectors, graph, entityVectors, relationVectors);
    }

    private List<GraphAssembler.ChunkExtraction> refineExtractions(List<io.github.lightrag.types.Chunk> chunks) {
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.PRIMARY_EXTRACTION, "extracting entities and relations");
        var primaryExtractions = chunks.stream()
            .map(chunk -> new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
            .toList();
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.PRIMARY_EXTRACTION, "primary extraction completed");
        if (!extractionRefinementOptions.enabled()) {
            progressListener.onStageSkipped(io.github.lightrag.api.TaskStage.REFINEMENT_EXTRACTION, "contextual refinement disabled");
            return extractionRefinementPipeline.refine(primaryExtractions);
        }
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.REFINEMENT_EXTRACTION, "running contextual refinement");
        var refined = extractionRefinementPipeline.refine(primaryExtractions);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.REFINEMENT_EXTRACTION, "contextual refinement completed");
        return refined;
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
            progressListener.onStageStarted(io.github.lightrag.api.TaskStage.COMMITTING, "committing storage mutations");
            synchronized (storageMutationMonitor) {
                storageProvider.writeAtomically(storage -> {
                    documentIngestor.persist(computed.prepared(), storage);
                    saveChunkVectors(computed.prepared().chunks(), computed.chunkVectors(), storage.vectorStore());
                    saveGraph(computed.graph().entities(), computed.graph().relations(), storage);
                    saveEntityVectors(computed.graph().entities(), computed.entityVectors(), storage.vectorStore());
                    saveRelationVectors(computed.graph().relations(), computed.relationVectors(), storage.vectorStore());
                    persistDocumentGraphState(computed);
                    storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                        computed.source().id(),
                        DocumentStatus.PROCESSED,
                        "processed %d chunks".formatted(computed.prepared().chunks().size()),
                        null
                    ));
                    return null;
                });
            }
            progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.COMMITTING, "committed storage mutations");
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
        long startedAtNanos = System.nanoTime();
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
        if (log.isDebugEnabled()) {
            log.debug(
                "Persisted graph batch with {} entities and {} relations in {} ms",
                entities.size(),
                relations.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
            );
        }
    }

    private void saveVectors(String namespace, List<VectorStore.VectorRecord> vectors, VectorStore vectorStore) {
        if (vectors.isEmpty()) {
            return;
        }
        vectorStore.saveAll(namespace, vectors);
    }

    private void saveChunkVectors(
        List<io.github.lightrag.types.Chunk> chunks,
        List<VectorStore.VectorRecord> vectors,
        VectorStore vectorStore
    ) {
        if (vectors.isEmpty()) {
            return;
        }
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(
                StorageSnapshots.CHUNK_NAMESPACE,
                HybridVectorPayloads.chunkPayloads(chunks, vectors)
            );
            return;
        }
        saveVectors(StorageSnapshots.CHUNK_NAMESPACE, vectors, vectorStore);
    }

    private void saveEntityVectors(List<Entity> entities, List<VectorStore.VectorRecord> vectors, VectorStore vectorStore) {
        if (vectors.isEmpty()) {
            return;
        }
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(
                StorageSnapshots.ENTITY_NAMESPACE,
                HybridVectorPayloads.entityPayloads(entities, vectors)
            );
            return;
        }
        saveVectors(StorageSnapshots.ENTITY_NAMESPACE, vectors, vectorStore);
    }

    private void saveRelationVectors(
        List<Relation> relations,
        List<VectorStore.VectorRecord> vectors,
        VectorStore vectorStore
    ) {
        if (vectors.isEmpty()) {
            return;
        }
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(
                StorageSnapshots.RELATION_NAMESPACE,
                HybridVectorPayloads.relationPayloads(relations, vectors)
            );
            return;
        }
        saveVectors(StorageSnapshots.RELATION_NAMESPACE, vectors, vectorStore);
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

    private List<VectorStore.VectorRecord> chunkVectors(List<io.github.lightrag.types.Chunk> chunks) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingBatcher.embedAll(chunks.stream().map(IndexingPipeline::chunkEmbeddingText).toList());
        return toVectorRecords(chunks.stream().map(io.github.lightrag.types.Chunk::id).toList(), embeddings);
    }

    private static String chunkEmbeddingText(io.github.lightrag.types.Chunk chunk) {
        var summary = chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_PARENT_SUMMARY, "").strip();
        var level = chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, "");
        if (!summary.isBlank() && ParentChildChunkBuilder.CHUNK_LEVEL_CHILD.equals(level)) {
            return summary + "\n" + chunk.text();
        }
        return chunk.text();
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

    private static String normalizedEntityType(String type) {
        if (type == null || type.isBlank()) {
            return "Other";
        }
        return type.strip();
    }

    private void persistDocumentGraphState(ComputedIngest computed) {
        var documentId = computed.source().id();
        var now = Instant.now();
        var snapshotVersion = storageProvider.documentGraphSnapshotStore().loadDocument(documentId)
            .map(DocumentGraphSnapshotStore.DocumentGraphSnapshot::version)
            .orElse(0) + 1;
        storageProvider.documentGraphSnapshotStore().saveDocument(new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            snapshotVersion,
            SnapshotStatus.READY,
            SnapshotSource.PRIMARY_EXTRACTION,
            computed.prepared().chunks().size(),
            now,
            now,
            null
        ));
        storageProvider.documentGraphSnapshotStore().saveChunks(
            documentId,
            toChunkGraphSnapshots(documentId, computed.prepared().chunks(), computed.extractions(), now)
        );
        storageProvider.documentGraphJournalStore().appendDocument(new DocumentGraphJournalStore.DocumentGraphJournal(
            documentId,
            snapshotVersion,
            GraphMaterializationStatus.MERGED,
            GraphMaterializationMode.AUTO,
            computed.graph().entities().size(),
            computed.graph().relations().size(),
            computed.graph().entities().size(),
            computed.graph().relations().size(),
            FailureStage.FINALIZING,
            now,
            now,
            null
        ));
        storageProvider.documentGraphJournalStore().appendChunks(
            documentId,
            toChunkGraphJournals(documentId, snapshotVersion, computed.extractions(), now)
        );
    }

    private List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> toChunkGraphSnapshots(
        String documentId,
        List<io.github.lightrag.types.Chunk> chunks,
        List<GraphAssembler.ChunkExtraction> extractions,
        Instant now
    ) {
        var chunksById = chunks.stream().collect(java.util.stream.Collectors.toMap(io.github.lightrag.types.Chunk::id, chunk -> chunk));
        return extractions.stream()
            .map(extraction -> {
                var chunk = chunksById.get(extraction.chunkId());
                return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
                    documentId,
                    extraction.chunkId(),
                    chunk.order(),
                    Integer.toHexString(chunk.text().hashCode()),
                    ChunkExtractStatus.SUCCEEDED,
                    extraction.extraction().entities().stream()
                        .map(entity -> new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                            entity.name(),
                            normalizedEntityType(entity.type()),
                            entity.description(),
                            entity.aliases()
                        ))
                        .toList(),
                    extraction.extraction().relations().stream()
                        .map(relation -> new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                            relation.sourceEntityName(),
                            relation.targetEntityName(),
                            relation.type(),
                            relation.description(),
                            relation.weight()
                        ))
                        .toList(),
                    now,
                    null
                );
            })
            .sorted(java.util.Comparator.comparingInt(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkOrder))
            .toList();
    }

    private List<DocumentGraphJournalStore.ChunkGraphJournal> toChunkGraphJournals(
        String documentId,
        int snapshotVersion,
        List<GraphAssembler.ChunkExtraction> extractions,
        Instant now
    ) {
        return extractions.stream()
            .map(extraction -> {
                var chunkGraph = graphAssembler.assemble(List.of(extraction));
                var entityKeys = chunkGraph.entities().stream().map(Entity::id).toList();
                var relationKeys = chunkGraph.relations().stream().map(Relation::id).toList();
                return new DocumentGraphJournalStore.ChunkGraphJournal(
                    documentId,
                    extraction.chunkId(),
                    snapshotVersion,
                    ChunkMergeStatus.SUCCEEDED,
                    ChunkGraphStatus.MATERIALIZED,
                    entityKeys,
                    relationKeys,
                    entityKeys,
                    relationKeys,
                    FailureStage.FINALIZING,
                    now,
                    null
                );
            })
            .toList();
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
        List<GraphAssembler.ChunkExtraction> extractions,
        List<VectorStore.VectorRecord> chunkVectors,
        GraphAssembler.Graph graph,
        List<VectorStore.VectorRecord> entityVectors,
        List<VectorStore.VectorRecord> relationVectors
    ) {
    }
}
