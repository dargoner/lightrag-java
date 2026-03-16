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
import io.github.lightragjava.types.Entity;
import io.github.lightragjava.types.Relation;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class IndexingPipeline {
    private static final int DEFAULT_CHUNK_WINDOW = 1_000;
    private static final int DEFAULT_CHUNK_OVERLAP = 100;

    private final AtomicStorageProvider storageProvider;
    private final DocumentIngestor documentIngestor;
    private final KnowledgeExtractor knowledgeExtractor;
    private final GraphAssembler graphAssembler;
    private final EmbeddingModel embeddingModel;
    private final Path snapshotPath;

    public IndexingPipeline(
        ChatModel chatModel,
        EmbeddingModel embeddingModel,
        AtomicStorageProvider storageProvider,
        Path snapshotPath
    ) {
        this.storageProvider = Objects.requireNonNull(storageProvider, "storageProvider");
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.snapshotPath = snapshotPath;
        this.documentIngestor = new DocumentIngestor(storageProvider, new FixedWindowChunker(DEFAULT_CHUNK_WINDOW, DEFAULT_CHUNK_OVERLAP));
        this.knowledgeExtractor = new KnowledgeExtractor(Objects.requireNonNull(chatModel, "chatModel"));
        this.graphAssembler = new GraphAssembler();
    }

    public void ingest(List<Document> documents) {
        for (var document : List.copyOf(Objects.requireNonNull(documents, "documents"))) {
            try {
                ingestOne(document);
                persistSnapshotIfConfigured();
            } catch (RuntimeException | Error failure) {
                persistSnapshotIfConfigured();
                throw failure;
            }
        }
    }

    private void ingestOne(Document document) {
        var source = Objects.requireNonNull(document, "document");
        saveStatus(new DocumentStatusStore.StatusRecord(source.id(), DocumentStatus.PROCESSING, "", null));
        try {
            var prepared = documentIngestor.prepare(List.of(source));
            var chunks = prepared.chunks();
            var chunkVectors = chunkVectors(chunks);

            var graph = graphAssembler.assemble(chunks.stream()
                .map(chunk -> new GraphAssembler.ChunkExtraction(chunk.id(), knowledgeExtractor.extract(chunk)))
                .toList());
            var entityVectors = entityVectors(graph.entities());
            var relationVectors = relationVectors(graph.relations());

            storageProvider.writeAtomically(storage -> {
                saveDocumentsAndChunks(prepared, storage);
                saveVectors(StorageSnapshots.CHUNK_NAMESPACE, chunkVectors, storage.vectorStore());
                saveGraph(graph.entities(), graph.relations(), storage);
                saveVectors(StorageSnapshots.ENTITY_NAMESPACE, entityVectors, storage.vectorStore());
                saveVectors(StorageSnapshots.RELATION_NAMESPACE, relationVectors, storage.vectorStore());
                storage.documentStatusStore().save(new DocumentStatusStore.StatusRecord(
                    source.id(),
                    DocumentStatus.PROCESSED,
                    "processed %d chunks".formatted(chunks.size()),
                    null
                ));
                return null;
            });
        } catch (RuntimeException | Error failure) {
            saveStatus(new DocumentStatusStore.StatusRecord(
                source.id(),
                DocumentStatus.FAILED,
                "",
                failure.getMessage()
            ));
            throw failure;
        }
    }

    private void saveStatus(DocumentStatusStore.StatusRecord statusRecord) {
        storageProvider.writeAtomically(storage -> {
            storage.documentStatusStore().save(statusRecord);
            return null;
        });
    }

    private void saveDocumentsAndChunks(
        DocumentIngestor.PreparedIngest prepared,
        AtomicStorageProvider.AtomicStorageView storage
    ) {
        for (var documentRecord : prepared.documentRecords()) {
            storage.documentStore().save(documentRecord);
        }
        for (var chunkRecord : prepared.chunkRecords()) {
            storage.chunkStore().save(chunkRecord);
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
        StorageSnapshots.persistIfConfigured(storageProvider, snapshotPath);
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
        var embeddings = embeddingModel.embedAll(chunks.stream().map(io.github.lightragjava.types.Chunk::text).toList());
        return toVectorRecords(chunks.stream().map(io.github.lightragjava.types.Chunk::id).toList(), embeddings);
    }

    List<VectorStore.VectorRecord> entityVectors(List<Entity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingModel.embedAll(entities.stream().map(IndexingPipeline::entitySummary).toList());
        return toVectorRecords(entities.stream().map(Entity::id).toList(), embeddings);
    }

    List<VectorStore.VectorRecord> relationVectors(List<Relation> relations) {
        if (relations.isEmpty()) {
            return List.of();
        }
        var embeddings = embeddingModel.embedAll(relations.stream().map(IndexingPipeline::relationSummary).toList());
        return toVectorRecords(relations.stream().map(Relation::id).toList(), embeddings);
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
}
