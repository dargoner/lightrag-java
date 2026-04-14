package io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkExtractStatus;
import io.github.lightrag.api.ChunkGraphMaterializationResult;
import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.DocumentChunkGraphStatus;
import io.github.lightrag.api.DocumentGraphInspection;
import io.github.lightrag.api.DocumentGraphMaterializationResult;
import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphChunkAction;
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
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.ExtractionResult;
import io.github.lightrag.types.Relation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class GraphMaterializationPipeline {
    private final AtomicStorageProvider storageProvider;
    private final KnowledgeExtractor knowledgeExtractor;
    private final ExtractionRefinementPipeline extractionRefinementPipeline;
    private final EmbeddingBatcher embeddingBatcher;
    private final GraphAssembler graphAssembler;
    private final ExtractionRefinementOptions extractionRefinementOptions;
    private final Path snapshotPath;
    private final TaskMetadataReporter metadataReporter;
    private final IndexingProgressListener progressListener;

    public GraphMaterializationPipeline(
        ChatModel extractionModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        ExtractionRefinementOptions extractionRefinementOptions,
        Path snapshotPath,
        TaskMetadataReporter metadataReporter,
        IndexingProgressListener progressListener
    ) {
        this(
            extractionModel,
            embeddingModel,
            storageProvider,
            extractionRefinementOptions,
            snapshotPath,
            metadataReporter,
            progressListener,
            KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES
        );
    }

    public GraphMaterializationPipeline(
        ChatModel extractionModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        ExtractionRefinementOptions extractionRefinementOptions,
        Path snapshotPath,
        TaskMetadataReporter metadataReporter,
        IndexingProgressListener progressListener,
        int entityExtractMaxGleaning,
        int maxExtractInputTokens,
        String entityExtractionLanguage,
        List<String> entityTypes
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.extractionRefinementOptions = extractionRefinementOptions == null
            ? ExtractionRefinementOptions.disabled()
            : extractionRefinementOptions;
        this.snapshotPath = snapshotPath;
        this.metadataReporter = metadataReporter == null ? TaskMetadataReporter.noop() : metadataReporter;
        this.progressListener = progressListener == null ? IndexingProgressListener.noop() : progressListener;
        this.knowledgeExtractor = new KnowledgeExtractor(
            Objects.requireNonNull(extractionModel, "extractionModel"),
            entityExtractMaxGleaning,
            maxExtractInputTokens,
            entityExtractionLanguage,
            entityTypes,
            this.extractionRefinementOptions.allowDeterministicAttributionFallback()
        );
        this.embeddingBatcher = new EmbeddingBatcher(Objects.requireNonNull(embeddingModel, "embeddingModel"), Integer.MAX_VALUE);
        this.graphAssembler = new GraphAssembler();
        this.extractionRefinementPipeline = new ExtractionRefinementPipeline(
            this.extractionRefinementOptions,
            new DefaultExtractionGapDetector(),
            new DefaultRefinementWindowResolver(),
            (window, ignored) -> this.knowledgeExtractor.extractWindow(window),
            new DefaultAttributionResolver(this.extractionRefinementOptions.allowDeterministicAttributionFallback()),
            new DefaultExtractionMergePolicy()
        );
    }

    public DocumentGraphInspection inspect(String documentId) {
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.SNAPSHOT_LOADING, "loading graph materialization state");
        var state = loadState(documentId);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.SNAPSHOT_LOADING, "loaded graph materialization state");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.GRAPH_INSPECTION, "inspecting graph materialization state");
        var inspection = toInspection(state);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.GRAPH_INSPECTION, inspection.summary());
        return inspection;
    }

    public DocumentGraphMaterializationResult materialize(String documentId, GraphMaterializationMode mode) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var requestedMode = Objects.requireNonNull(mode, "mode");
        var inspection = inspect(normalizedDocumentId);
        var executedMode = resolveMode(inspection, requestedMode);
        var state = loadState(normalizedDocumentId);
        var snapshotRecovered = false;
        var effectiveState = state;

        if (executedMode == GraphMaterializationMode.REBUILD) {
            progressListener.onStageStarted(io.github.lightrag.api.TaskStage.SNAPSHOT_RECOVERY, "rebuilding graph snapshot from stored chunks");
            effectiveState = rebuildSnapshot(normalizedDocumentId, state);
            snapshotRecovered = true;
            progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.SNAPSHOT_RECOVERY, "rebuilt graph snapshot from stored chunks");
        } else {
            progressListener.onStageSkipped(io.github.lightrag.api.TaskStage.SNAPSHOT_RECOVERY, "snapshot recovery not required");
        }

        materializeDocumentState(effectiveState, executedMode);
        var after = toInspection(loadState(normalizedDocumentId));
        metadataReporter.updateMetadata(Map.of(
            "documentId", normalizedDocumentId,
            "requestedMode", requestedMode.name(),
            "executedMode", executedMode.name(),
            "finalStatus", after.graphStatus().name()
        ));
        return new DocumentGraphMaterializationResult(
            normalizedDocumentId,
            requestedMode,
            executedMode,
            after.graphStatus(),
            effectiveState.snapshotVersion(),
            after.expectedEntityCount(),
            after.expectedRelationCount(),
            after.expectedEntityCount() - after.missingEntityKeys().size(),
            after.expectedRelationCount() - after.missingRelationKeys().size(),
            !snapshotRecovered,
            snapshotRecovered,
            after.summary(),
            null
        );
    }

    public DocumentChunkGraphStatus getChunkStatus(String documentId, String chunkId) {
        var state = loadState(documentId);
        return toChunkStatus(state, requireNonBlank(chunkId, "chunkId"));
    }

    public List<DocumentChunkGraphStatus> listChunkStatuses(String documentId) {
        var state = loadState(documentId);
        return state.chunkSnapshots().stream()
            .sorted(java.util.Comparator.comparingInt(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkOrder))
            .map(snapshot -> toChunkStatus(state, snapshot.chunkId()))
            .toList();
    }

    public ChunkGraphMaterializationResult resumeChunk(String documentId, String chunkId) {
        return materializeChunk(requireNonBlank(documentId, "documentId"), requireNonBlank(chunkId, "chunkId"), GraphChunkAction.RESUME);
    }

    public ChunkGraphMaterializationResult repairChunk(String documentId, String chunkId) {
        return materializeChunk(requireNonBlank(documentId, "documentId"), requireNonBlank(chunkId, "chunkId"), GraphChunkAction.REPAIR);
    }

    private ChunkGraphMaterializationResult materializeChunk(String documentId, String chunkId, GraphChunkAction action) {
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.SNAPSHOT_LOADING, "loading chunk graph state");
        var state = loadState(documentId);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.SNAPSHOT_LOADING, "loaded chunk graph state");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.GRAPH_INSPECTION, "inspecting chunk graph state");
        var before = toChunkStatus(state, chunkId);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.GRAPH_INSPECTION, "inspected chunk graph state");
        if (before.graphStatus() == ChunkGraphStatus.MATERIALIZED) {
            metadataReporter.updateMetadata(Map.of(
                "documentId", documentId,
                "chunkId", chunkId,
                "requestedAction", action.name(),
                "finalStatus", ChunkGraphStatus.MATERIALIZED.name()
            ));
            return new ChunkGraphMaterializationResult(
                documentId,
                chunkId,
                action,
                ChunkGraphStatus.MATERIALIZED,
                before.expectedEntityCount(),
                before.expectedRelationCount(),
                before.expectedEntityCount(),
                before.expectedRelationCount(),
                "chunk graph already materialized",
                null
            );
        }

        var chunkSnapshot = state.chunkSnapshotsById().get(chunkId);
        if (chunkSnapshot == null) {
            throw new NoSuchElementException("chunk graph snapshot does not exist: " + chunkId);
        }
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.ENTITY_MATERIALIZATION, "materializing chunk entities");
        var chunkGraph = assembleChunkGraph(chunkSnapshot);
        writeChunkMaterialization(state, chunkSnapshot, chunkGraph, action);
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.ENTITY_MATERIALIZATION, "materialized chunk entities");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.RELATION_MATERIALIZATION, "materializing chunk relations");
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.RELATION_MATERIALIZATION, "materialized chunk relations");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_REPAIR, "repairing chunk graph vectors");
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_REPAIR, "repaired chunk graph vectors");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.FINALIZING, "finalizing chunk graph materialization");
        persistSnapshotIfConfigured();
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.FINALIZING, "chunk graph materialization finalized");

        var after = toChunkStatus(loadState(documentId), chunkId);
        metadataReporter.updateMetadata(Map.of(
            "documentId", documentId,
            "chunkId", chunkId,
            "requestedAction", action.name(),
            "finalStatus", after.graphStatus().name()
        ));
        return new ChunkGraphMaterializationResult(
            documentId,
            chunkId,
            action,
            after.graphStatus(),
            after.expectedEntityCount(),
            after.expectedRelationCount(),
            after.materializedEntityCount(),
            after.materializedRelationCount(),
            "chunk graph materialized",
            after.errorMessage()
        );
    }

    private void materializeDocumentState(MaterializationState state, GraphMaterializationMode mode) {
        if (state.chunkSnapshots().isEmpty()) {
            throw new IllegalStateException("document graph snapshot does not exist: " + state.documentId());
        }
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.ENTITY_MATERIALIZATION, "materializing document entities");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.RELATION_MATERIALIZATION, "materializing document relations");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.VECTOR_REPAIR, "repairing graph vectors");
        storageProvider.writeAtomically(storage -> {
            saveGraph(state.expectedGraph().entities(), state.expectedGraph().relations(), storage);
            saveEntityVectors(state.expectedGraph().entities(), storage.vectorStore());
            saveRelationVectors(state.expectedGraph().relations(), storage.vectorStore());
            storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                state.documentId(),
                DocumentStatus.PROCESSED,
                "graph materialized in %s mode".formatted(mode.name()),
                null
            ));
            appendSuccessJournals(state, mode);
            return null;
        });
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.ENTITY_MATERIALIZATION, "materialized document entities");
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.RELATION_MATERIALIZATION, "materialized document relations");
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.VECTOR_REPAIR, "repaired graph vectors");
        progressListener.onStageStarted(io.github.lightrag.api.TaskStage.FINALIZING, "finalizing graph materialization");
        persistSnapshotIfConfigured();
        progressListener.onStageSucceeded(io.github.lightrag.api.TaskStage.FINALIZING, "graph materialization finalized");
    }

    private MaterializationState rebuildSnapshot(String documentId, MaterializationState currentState) {
        var storedChunks = storageProvider.chunkStore().listByDocument(documentId).stream()
            .map(chunk -> new Chunk(chunk.id(), chunk.documentId(), chunk.text(), chunk.tokenCount(), chunk.order(), chunk.metadata()))
            .toList();
        if (storedChunks.isEmpty()) {
            throw new IllegalStateException("cannot rebuild graph snapshot because stored chunks do not exist: " + documentId);
        }
        var rebuiltExtractions = refineExtractions(storedChunks);
        var now = Instant.now();
        var version = currentState.snapshotVersion() + 1;
        var documentSnapshot = new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            version,
            SnapshotStatus.READY,
            SnapshotSource.RECOVERED_FROM_STORAGE,
            storedChunks.size(),
            now,
            now,
            null
        );
        var chunkSnapshots = toChunkSnapshots(documentId, rebuiltExtractions, storedChunks, now);
        storageProvider.writeAtomically(storage -> {
            storage.documentGraphSnapshotStore().saveDocument(documentSnapshot);
            storage.documentGraphSnapshotStore().saveChunks(documentId, chunkSnapshots);
            storage.documentGraphJournalStore().delete(documentId);
            return null;
        });
        return loadState(documentId);
    }

    private void writeChunkMaterialization(
        MaterializationState state,
        DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot,
        GraphAssembler.Graph chunkGraph,
        GraphChunkAction action
    ) {
        storageProvider.writeAtomically(storage -> {
            saveGraph(chunkGraph.entities(), chunkGraph.relations(), storage);
            saveEntityVectors(chunkGraph.entities(), storage.vectorStore());
            saveRelationVectors(chunkGraph.relations(), storage.vectorStore());
            storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                state.documentId(),
                DocumentStatus.PROCESSED,
                "chunk graph materialized for %s".formatted(chunkSnapshot.chunkId()),
                null
            ));
            appendChunkJournal(state, chunkSnapshot, chunkGraph, action);
            appendDocumentJournalFromState(state.documentId(), action == GraphChunkAction.REPAIR
                ? GraphMaterializationMode.REPAIR
                : GraphMaterializationMode.RESUME);
            return null;
        });
    }

    private void appendSuccessJournals(MaterializationState state, GraphMaterializationMode mode) {
        var now = Instant.now();
        storageProvider.documentGraphJournalStore().appendDocument(new DocumentGraphJournalStore.DocumentGraphJournal(
            state.documentId(),
            state.snapshotVersion(),
            GraphMaterializationStatus.MERGED,
            mode,
            state.expectedEntityIds().size(),
            state.expectedRelationIds().size(),
            state.expectedEntityIds().size(),
            state.expectedRelationIds().size(),
            FailureStage.FINALIZING,
            now,
            now,
            null
        ));
        storageProvider.documentGraphJournalStore().appendChunks(state.documentId(), state.chunkSnapshots().stream()
            .map(snapshot -> {
                var chunkGraph = assembleChunkGraph(snapshot);
                var entityKeys = chunkGraph.entities().stream().map(Entity::id).toList();
                var relationKeys = chunkGraph.relations().stream().map(Relation::id).toList();
                return new DocumentGraphJournalStore.ChunkGraphJournal(
                    state.documentId(),
                    snapshot.chunkId(),
                    state.snapshotVersion(),
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
            .toList());
    }

    private void appendChunkJournal(
        MaterializationState state,
        DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot,
        GraphAssembler.Graph chunkGraph,
        GraphChunkAction action
    ) {
        var entityKeys = chunkGraph.entities().stream().map(Entity::id).toList();
        var relationKeys = chunkGraph.relations().stream().map(Relation::id).toList();
        storageProvider.documentGraphJournalStore().appendChunks(state.documentId(), List.of(
            new DocumentGraphJournalStore.ChunkGraphJournal(
                state.documentId(),
                chunkSnapshot.chunkId(),
                state.snapshotVersion(),
                ChunkMergeStatus.SUCCEEDED,
                ChunkGraphStatus.MATERIALIZED,
                entityKeys,
                relationKeys,
                entityKeys,
                relationKeys,
                FailureStage.FINALIZING,
                Instant.now(),
                null
            )
        ));
    }

    private void appendDocumentJournalFromState(String documentId, GraphMaterializationMode mode) {
        var inspection = toInspection(loadState(documentId));
        storageProvider.documentGraphJournalStore().appendDocument(new DocumentGraphJournalStore.DocumentGraphJournal(
            documentId,
            inspection.snapshotVersion(),
            inspection.graphStatus(),
            mode,
            inspection.expectedEntityCount(),
            inspection.expectedRelationCount(),
            inspection.expectedEntityCount() - inspection.missingEntityKeys().size(),
            inspection.expectedRelationCount() - inspection.missingRelationKeys().size(),
            inspection.graphStatus() == GraphMaterializationStatus.MERGED
                ? FailureStage.FINALIZING
                : FailureStage.RELATION_MATERIALIZATION,
            Instant.now(),
            Instant.now(),
            inspection.graphStatus() == GraphMaterializationStatus.MERGED ? null : inspection.summary()
        ));
    }

    private DocumentGraphInspection toInspection(MaterializationState state) {
        var missingEntities = difference(state.expectedEntityIds(), state.actualEntityIds());
        var missingRelations = difference(state.expectedRelationIds(), state.actualRelationIds());
        var orphanEntities = difference(state.actualEntityIds(), state.expectedEntityIds());
        var orphanRelations = difference(state.actualRelationIds(), state.expectedRelationIds());
        var graphStatus = determineDocumentGraphStatus(state, missingEntities, missingRelations, orphanEntities, orphanRelations);
        var recommendedMode = determineRecommendedMode(state, graphStatus, missingEntities, missingRelations, orphanEntities, orphanRelations);
        return new DocumentGraphInspection(
            state.documentId(),
            state.documentStatus().status(),
            graphStatus,
            state.snapshotStatus(),
            state.snapshotVersion(),
            state.expectedEntityIds().size(),
            state.expectedRelationIds().size(),
            state.expectedEntityIds().size() - missingEntities.size(),
            state.expectedRelationIds().size() - missingRelations.size(),
            missingEntities,
            missingRelations,
            orphanEntities,
            orphanRelations,
            recommendedMode,
            state.snapshotStatus() != SnapshotStatus.FAILED || !state.storedChunks().isEmpty(),
            summarizeDocumentState(graphStatus, missingEntities, missingRelations, orphanEntities, orphanRelations)
        );
    }

    private DocumentChunkGraphStatus toChunkStatus(MaterializationState state, String chunkId) {
        var chunkSnapshot = state.chunkSnapshotsById().get(chunkId);
        if (chunkSnapshot == null) {
            throw new NoSuchElementException("chunk graph snapshot does not exist: " + chunkId);
        }
        var expectedChunkGraph = assembleChunkGraph(chunkSnapshot);
        var expectedEntities = expectedChunkGraph.entities().stream().map(Entity::id).toList();
        var expectedRelations = expectedChunkGraph.relations().stream().map(Relation::id).toList();
        var actualEntities = state.actualEntitiesByChunkId().getOrDefault(chunkId, Set.of()).stream().sorted().toList();
        var actualRelations = state.actualRelationsByChunkId().getOrDefault(chunkId, Set.of()).stream().sorted().toList();
        var missingEntities = difference(expectedEntities, actualEntities);
        var missingRelations = difference(expectedRelations, actualRelations);
        var journal = state.latestChunkJournalById().get(chunkId);
        var graphStatus = determineChunkGraphStatus(chunkSnapshot, actualEntities, actualRelations, missingEntities, missingRelations, journal);
        return new DocumentChunkGraphStatus(
            state.documentId(),
            chunkId,
            chunkSnapshot.chunkOrder(),
            chunkSnapshot.extractStatus(),
            journal == null ? ChunkMergeStatus.NOT_STARTED : journal.mergeStatus(),
            graphStatus,
            expectedEntities.size(),
            expectedRelations.size(),
            actualEntities.size(),
            actualRelations.size(),
            missingEntities,
            missingRelations,
            chunkSnapshot.extractStatus() == ChunkExtractStatus.SUCCEEDED,
            determineRecommendedAction(graphStatus, actualEntities, actualRelations, missingEntities, missingRelations),
            journal == null ? chunkSnapshot.errorMessage() : journal.errorMessage()
        );
    }

    private MaterializationState loadState(String documentId) {
        var normalizedDocumentId = requireNonBlank(documentId, "documentId");
        var chunkSnapshots = storageProvider.documentGraphSnapshotStore().listChunks(normalizedDocumentId);
        var documentSnapshot = storageProvider.documentGraphSnapshotStore().loadDocument(normalizedDocumentId).orElse(null);
        var latestDocumentJournal = latest(storageProvider.documentGraphJournalStore().listDocumentJournals(normalizedDocumentId), DocumentGraphJournalStore.DocumentGraphJournal::updatedAt);
        var latestChunkJournals = latestByKey(
            storageProvider.documentGraphJournalStore().listChunkJournals(normalizedDocumentId),
            DocumentGraphJournalStore.ChunkGraphJournal::chunkId,
            DocumentGraphJournalStore.ChunkGraphJournal::updatedAt
        );
        var storedChunks = storageProvider.chunkStore().listByDocument(normalizedDocumentId).stream()
            .map(chunk -> new Chunk(chunk.id(), chunk.documentId(), chunk.text(), chunk.tokenCount(), chunk.order(), chunk.metadata()))
            .toList();
        var expectedGraph = chunkSnapshots.isEmpty()
            ? new GraphAssembler.Graph(List.of(), List.of())
            : graphAssembler.assemble(toChunkExtractions(chunkSnapshots));
        var chunkIds = chunkSnapshots.stream().map(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        var actualEntities = storageProvider.graphStore().allEntities().stream()
            .filter(entity -> intersects(entity.sourceChunkIds(), chunkIds))
            .toList();
        var actualRelations = storageProvider.graphStore().allRelations().stream()
            .filter(relation -> intersects(relation.sourceChunkIds(), chunkIds))
            .toList();
        return new MaterializationState(
            normalizedDocumentId,
            documentSnapshot,
            chunkSnapshots,
            latestDocumentJournal,
            latestChunkJournals,
            storageProvider.documentStatusStore().load(normalizedDocumentId)
                .orElse(new DocumentStatusStore.StatusRecord(normalizedDocumentId, DocumentStatus.FAILED, "", "document status does not exist")),
            expectedGraph,
            actualEntities,
            actualRelations,
            storedChunks
        );
    }

    private GraphMaterializationMode resolveMode(DocumentGraphInspection inspection, GraphMaterializationMode requestedMode) {
        if (requestedMode != GraphMaterializationMode.AUTO) {
            return requestedMode;
        }
        return inspection.recommendedMode() == GraphMaterializationMode.AUTO
            ? GraphMaterializationMode.RESUME
            : inspection.recommendedMode();
    }

    private List<GraphAssembler.ChunkExtraction> refineExtractions(List<Chunk> chunks) {
        var primaryExtractions = chunks.stream()
            .map(chunk -> new PrimaryChunkExtraction(chunk, knowledgeExtractor.extract(chunk)))
            .toList();
        return extractionRefinementPipeline.refine(primaryExtractions);
    }

    private List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> toChunkSnapshots(
        String documentId,
        List<GraphAssembler.ChunkExtraction> extractions,
        List<Chunk> chunks,
        Instant now
    ) {
        var chunksById = chunks.stream().collect(Collectors.toMap(Chunk::id, Function.identity()));
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

    private List<GraphAssembler.ChunkExtraction> toChunkExtractions(List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunkSnapshots) {
        return chunkSnapshots.stream()
            .map(snapshot -> new GraphAssembler.ChunkExtraction(
                snapshot.chunkId(),
                new ExtractionResult(
                    snapshot.entities().stream()
                        .map(entity -> new io.github.lightrag.types.ExtractedEntity(
                            entity.name(),
                            entity.type(),
                            entity.description() == null ? "" : entity.description(),
                            entity.aliases()
                        ))
                        .toList(),
                    snapshot.relations().stream()
                        .map(relation -> new io.github.lightrag.types.ExtractedRelation(
                            relation.sourceEntityName(),
                            relation.targetEntityName(),
                            relation.type(),
                            relation.description() == null ? "" : relation.description(),
                            relation.weight()
                        ))
                        .toList(),
                    List.of()
                )
            ))
            .toList();
    }

    private GraphAssembler.Graph assembleChunkGraph(DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot) {
        return graphAssembler.assemble(toChunkExtractions(List.of(chunkSnapshot)));
    }

    private void saveGraph(List<Entity> entities, List<Relation> relations, AtomicStorageProvider.AtomicStorageView storage) {
        var graphStore = storage.graphStore();
        if (!entities.isEmpty()) {
            var existingEntitiesById = new LinkedHashMap<String, GraphStore.EntityRecord>();
            for (var entity : graphStore.loadEntities(distinctIds(entities.stream().map(Entity::id).toList()))) {
                existingEntitiesById.put(entity.id(), entity);
            }
            var mergedEntities = new LinkedHashMap<String, GraphStore.EntityRecord>();
            for (var entity : entities) {
                var current = mergedEntities.get(entity.id());
                var mergedEntity = current != null
                    ? mergeEntity(current, entity)
                    : existingEntitiesById.containsKey(entity.id())
                        ? mergeEntity(existingEntitiesById.get(entity.id()), entity)
                        : toEntityRecord(entity);
                mergedEntities.remove(entity.id());
                mergedEntities.put(entity.id(), mergedEntity);
            }
            graphStore.saveEntities(List.copyOf(mergedEntities.values()));
        }
        if (!relations.isEmpty()) {
            var existingRelationsById = new LinkedHashMap<String, GraphStore.RelationRecord>();
            for (var relation : graphStore.loadRelations(distinctIds(relations.stream().map(Relation::id).toList()))) {
                existingRelationsById.put(relation.id(), relation);
            }
            var mergedRelations = new LinkedHashMap<String, GraphStore.RelationRecord>();
            for (var relation : relations) {
                var current = mergedRelations.get(relation.id());
                var mergedRelation = current != null
                    ? mergeRelation(current, relation)
                    : existingRelationsById.containsKey(relation.id())
                        ? mergeRelation(existingRelationsById.get(relation.id()), relation)
                        : toRelationRecord(relation);
                mergedRelations.remove(relation.id());
                mergedRelations.put(relation.id(), mergedRelation);
            }
            graphStore.saveRelations(List.copyOf(mergedRelations.values()));
        }
    }

    private static List<String> distinctIds(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private void saveEntityVectors(List<Entity> entities, VectorStore vectorStore) {
        if (entities.isEmpty()) {
            return;
        }
        var vectors = toVectorRecords(entities.stream().map(Entity::id).toList(), embeddingBatcher.embedAll(
            entities.stream().map(GraphMaterializationPipeline::entitySummary).toList()
        ));
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(StorageSnapshots.ENTITY_NAMESPACE, HybridVectorPayloads.entityPayloads(entities, vectors));
            return;
        }
        vectorStore.saveAll(StorageSnapshots.ENTITY_NAMESPACE, vectors);
    }

    private void saveRelationVectors(List<Relation> relations, VectorStore vectorStore) {
        if (relations.isEmpty()) {
            return;
        }
        var vectors = toVectorRecords(relations.stream().map(Relation::id).toList(), embeddingBatcher.embedAll(
            relations.stream().map(GraphMaterializationPipeline::relationSummary).toList()
        ));
        if (vectorStore instanceof HybridVectorStore hybridVectorStore) {
            hybridVectorStore.saveAllEnriched(StorageSnapshots.RELATION_NAMESPACE, HybridVectorPayloads.relationPayloads(relations, vectors));
            return;
        }
        vectorStore.saveAll(StorageSnapshots.RELATION_NAMESPACE, vectors);
    }

    private void persistSnapshotIfConfigured() {
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
    }

    private static GraphMaterializationStatus determineDocumentGraphStatus(
        MaterializationState state,
        List<String> missingEntities,
        List<String> missingRelations,
        List<String> orphanEntities,
        List<String> orphanRelations
    ) {
        if (state.chunkSnapshots().isEmpty()) {
            return GraphMaterializationStatus.MISSING;
        }
        if (missingEntities.isEmpty() && missingRelations.isEmpty() && orphanEntities.isEmpty() && orphanRelations.isEmpty()) {
            return GraphMaterializationStatus.MERGED;
        }
        if (!state.actualEntityIds().isEmpty() || !state.actualRelationIds().isEmpty()) {
            return GraphMaterializationStatus.PARTIAL;
        }
        if (state.latestDocumentJournal() != null && state.latestDocumentJournal().status() == GraphMaterializationStatus.FAILED) {
            return GraphMaterializationStatus.FAILED;
        }
        return GraphMaterializationStatus.MISSING;
    }

    private static GraphMaterializationMode determineRecommendedMode(
        MaterializationState state,
        GraphMaterializationStatus graphStatus,
        List<String> missingEntities,
        List<String> missingRelations,
        List<String> orphanEntities,
        List<String> orphanRelations
    ) {
        if (state.chunkSnapshots().isEmpty()) {
            return GraphMaterializationMode.REBUILD;
        }
        if (graphStatus == GraphMaterializationStatus.MERGED) {
            return GraphMaterializationMode.AUTO;
        }
        if (!orphanEntities.isEmpty() || !orphanRelations.isEmpty()) {
            return GraphMaterializationMode.REPAIR;
        }
        if (!missingEntities.isEmpty() || !missingRelations.isEmpty()) {
            return state.actualEntityIds().isEmpty() && state.actualRelationIds().isEmpty()
                ? GraphMaterializationMode.RESUME
                : GraphMaterializationMode.REPAIR;
        }
        return GraphMaterializationMode.RESUME;
    }

    private static String summarizeDocumentState(
        GraphMaterializationStatus graphStatus,
        List<String> missingEntities,
        List<String> missingRelations,
        List<String> orphanEntities,
        List<String> orphanRelations
    ) {
        return "graphStatus=%s missingEntities=%d missingRelations=%d orphanEntities=%d orphanRelations=%d".formatted(
            graphStatus.name(),
            missingEntities.size(),
            missingRelations.size(),
            orphanEntities.size(),
            orphanRelations.size()
        );
    }

    private static ChunkGraphStatus determineChunkGraphStatus(
        DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot,
        List<String> actualEntities,
        List<String> actualRelations,
        List<String> missingEntities,
        List<String> missingRelations,
        DocumentGraphJournalStore.ChunkGraphJournal journal
    ) {
        if (chunkSnapshot.extractStatus() == ChunkExtractStatus.FAILED) {
            return ChunkGraphStatus.FAILED;
        }
        if (missingEntities.isEmpty() && missingRelations.isEmpty()) {
            return ChunkGraphStatus.MATERIALIZED;
        }
        if (!actualEntities.isEmpty() || !actualRelations.isEmpty()) {
            return ChunkGraphStatus.PARTIAL;
        }
        if (journal != null && journal.graphStatus() == ChunkGraphStatus.FAILED) {
            return ChunkGraphStatus.FAILED;
        }
        return ChunkGraphStatus.MISSING;
    }

    private static GraphChunkAction determineRecommendedAction(
        ChunkGraphStatus graphStatus,
        List<String> actualEntities,
        List<String> actualRelations,
        List<String> missingEntities,
        List<String> missingRelations
    ) {
        if (graphStatus == ChunkGraphStatus.MATERIALIZED) {
            return GraphChunkAction.NONE;
        }
        if (!actualEntities.isEmpty() || !actualRelations.isEmpty()) {
            return GraphChunkAction.REPAIR;
        }
        if (!missingEntities.isEmpty() || !missingRelations.isEmpty()) {
            return GraphChunkAction.RESUME;
        }
        return GraphChunkAction.NONE;
    }

    private static List<String> difference(Collection<String> left, Collection<String> right) {
        var rightSet = new LinkedHashSet<>(right);
        return left.stream()
            .filter(value -> !rightSet.contains(value))
            .sorted()
            .toList();
    }

    private static boolean intersects(List<String> sourceChunkIds, Set<String> expectedChunkIds) {
        for (var sourceChunkId : sourceChunkIds) {
            if (expectedChunkIds.contains(sourceChunkId)) {
                return true;
            }
        }
        return false;
    }

    private static <T, K> Map<K, T> latestByKey(List<T> values, Function<T, K> keyExtractor, Function<T, Instant> timeExtractor) {
        var latest = new LinkedHashMap<K, T>();
        for (var value : values) {
            var key = keyExtractor.apply(value);
            var existing = latest.get(key);
            if (existing == null || timeExtractor.apply(existing).isBefore(timeExtractor.apply(value))) {
                latest.put(key, value);
            }
        }
        return Map.copyOf(latest);
    }

    private static <T> T latest(List<T> values, Function<T, Instant> timeExtractor) {
        T latest = null;
        for (var value : values) {
            if (latest == null || timeExtractor.apply(latest).isBefore(timeExtractor.apply(value))) {
                latest = value;
            }
        }
        return latest;
    }

    private static List<VectorStore.VectorRecord> toVectorRecords(List<String> ids, List<List<Double>> embeddings) {
        if (ids.size() != embeddings.size()) {
            throw new IllegalStateException("embedding count does not match indexed item count");
        }
        var vectors = new ArrayList<VectorStore.VectorRecord>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            vectors.add(new VectorStore.VectorRecord(ids.get(index), embeddings.get(index)));
        }
        return List.copyOf(vectors);
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

    private static List<String> union(List<String> left, List<String> right) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(left);
        merged.addAll(right);
        return List.copyOf(merged);
    }

    private static String requireNonBlank(String value, String label) {
        Objects.requireNonNull(value, label);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private record MaterializationState(
        String documentId,
        DocumentGraphSnapshotStore.DocumentGraphSnapshot documentSnapshot,
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunkSnapshots,
        DocumentGraphJournalStore.DocumentGraphJournal latestDocumentJournal,
        Map<String, DocumentGraphJournalStore.ChunkGraphJournal> latestChunkJournalById,
        DocumentStatusStore.StatusRecord documentStatus,
        GraphAssembler.Graph expectedGraph,
        List<GraphStore.EntityRecord> actualEntities,
        List<GraphStore.RelationRecord> actualRelations,
        List<Chunk> storedChunks
    ) {
        private MaterializationState {
            chunkSnapshots = List.copyOf(chunkSnapshots);
            latestChunkJournalById = Map.copyOf(latestChunkJournalById);
            actualEntities = List.copyOf(actualEntities);
            actualRelations = List.copyOf(actualRelations);
            storedChunks = List.copyOf(storedChunks);
        }

        private Map<String, DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunkSnapshotsById() {
            return chunkSnapshots.stream().collect(Collectors.toMap(
                DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId,
                Function.identity(),
                (left, right) -> right,
                LinkedHashMap::new
            ));
        }

        private Set<String> expectedEntityIds() {
            return expectedGraph.entities().stream().map(Entity::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private Set<String> expectedRelationIds() {
            return expectedGraph.relations().stream().map(Relation::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private Set<String> actualEntityIds() {
            return actualEntities.stream().map(GraphStore.EntityRecord::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private Set<String> actualRelationIds() {
            return actualRelations.stream().map(GraphStore.RelationRecord::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private Map<String, Set<String>> actualEntitiesByChunkId() {
            var grouped = new LinkedHashMap<String, Set<String>>();
            for (var entity : actualEntities) {
                for (var chunkId : entity.sourceChunkIds()) {
                    grouped.computeIfAbsent(chunkId, ignored -> new LinkedHashSet<>()).add(entity.id());
                }
            }
            return grouped;
        }

        private Map<String, Set<String>> actualRelationsByChunkId() {
            var grouped = new LinkedHashMap<String, Set<String>>();
            for (var relation : actualRelations) {
                for (var chunkId : relation.sourceChunkIds()) {
                    grouped.computeIfAbsent(chunkId, ignored -> new LinkedHashSet<>()).add(relation.id());
                }
            }
            return grouped;
        }

        private SnapshotStatus snapshotStatus() {
            return documentSnapshot == null ? SnapshotStatus.FAILED : documentSnapshot.status();
        }

        private int snapshotVersion() {
            return documentSnapshot == null ? 0 : documentSnapshot.version();
        }
    }
}
