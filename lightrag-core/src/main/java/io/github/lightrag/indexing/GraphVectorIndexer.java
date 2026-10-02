package io.github.lightrag.indexing;

import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;

import java.util.List;
import java.util.Objects;

/**
 * The embedding texts, id-to-vector builders and namespace writers shared by the indexing pipelines and the
 * offline rebuild tool, so a rebuilt index has exactly the shape ingestion produces. Writers upgrade to the
 * enriched payloads when the vector store is a {@link HybridVectorStore}.
 */
public final class GraphVectorIndexer {
    private GraphVectorIndexer() {
    }

    public static String chunkEmbeddingText(Chunk chunk) {
        var summary = chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_PARENT_SUMMARY, "").strip();
        var level = chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_CHUNK_LEVEL, "");
        if (!summary.isBlank() && ParentChildChunkBuilder.CHUNK_LEVEL_CHILD.equals(level)) {
            return summary + "\n" + chunk.text();
        }
        return chunk.text();
    }

    public static String entityEmbeddingText(Entity entity) {
        return "%s\n%s\n%s\n%s".formatted(
            entity.name(),
            entity.type(),
            entity.description(),
            String.join(", ", entity.aliases())
        );
    }

    public static String relationEmbeddingText(Relation relation) {
        return "%s\n%s\n%s\n%s".formatted(
            relation.srcId(),
            relation.keywords(),
            relation.tgtId(),
            relation.description()
        );
    }

    public static List<VectorStore.VectorRecord> chunkVectors(
        EmbeddingModel embeddingModel,
        int embeddingBatchSize,
        List<Chunk> chunks
    ) {
        var sources = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        if (sources.isEmpty()) {
            return List.of();
        }
        var batcher = new EmbeddingBatcher(embeddingModel, embeddingBatchSize);
        var embeddings = batcher.embedAll(sources.stream().map(GraphVectorIndexer::chunkEmbeddingText).toList());
        return toVectorRecords(sources.stream().map(Chunk::id).toList(), embeddings);
    }

    public static List<VectorStore.VectorRecord> entityVectors(
        EmbeddingModel embeddingModel,
        int embeddingBatchSize,
        List<Entity> entities
    ) {
        var sources = List.copyOf(Objects.requireNonNull(entities, "entities"));
        if (sources.isEmpty()) {
            return List.of();
        }
        var batcher = new EmbeddingBatcher(embeddingModel, embeddingBatchSize);
        var embeddings = batcher.embedAll(sources.stream().map(GraphVectorIndexer::entityEmbeddingText).toList());
        return toVectorRecords(sources.stream().map(Entity::id).toList(), embeddings);
    }

    public static List<VectorStore.VectorRecord> relationVectors(
        EmbeddingModel embeddingModel,
        int embeddingBatchSize,
        List<Relation> relations
    ) {
        var sources = List.copyOf(Objects.requireNonNull(relations, "relations"));
        if (sources.isEmpty()) {
            return List.of();
        }
        var batcher = new EmbeddingBatcher(embeddingModel, embeddingBatchSize);
        var embeddings = batcher.embedAll(sources.stream().map(GraphVectorIndexer::relationEmbeddingText).toList());
        return toVectorRecords(sources.stream().map(Relation::id).toList(), embeddings);
    }

    public static void saveChunkVectors(
        List<Chunk> chunks,
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

    public static void saveEntityVectors(
        List<Entity> entities,
        List<VectorStore.VectorRecord> vectors,
        VectorStore vectorStore
    ) {
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

    public static void saveRelationVectors(
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

    public static Entity toEntity(GraphStore.EntityRecord record) {
        return new Entity(
            record.id(),
            record.name(),
            record.type(),
            record.description(),
            record.aliases(),
            record.sourceChunkIds(),
            record.filePath()
        );
    }

    public static Relation toRelation(GraphStore.RelationRecord record) {
        return new Relation(
            record.relationId(),
            record.srcId(),
            record.tgtId(),
            record.keywords(),
            record.description(),
            record.weight(),
            record.sourceId(),
            record.filePath()
        );
    }

    public static Chunk toChunk(ChunkStore.ChunkRecord record) {
        return new Chunk(
            record.id(),
            record.documentId(),
            record.text(),
            record.tokenCount(),
            record.order(),
            record.metadata()
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

    private static void saveVectors(String namespace, List<VectorStore.VectorRecord> vectors, VectorStore vectorStore) {
        if (vectors.isEmpty()) {
            return;
        }
        vectorStore.saveAll(namespace, vectors);
    }
}
