package io.github.lightrag.indexing;

import io.github.lightrag.storage.HybridVectorStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Entity;
import io.github.lightrag.types.Relation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HybridVectorPayloads {
    private HybridVectorPayloads() {
    }

    public static List<HybridVectorStore.EnrichedVectorRecord> chunkPayloads(
        List<Chunk> chunks,
        List<VectorStore.VectorRecord> vectors
    ) {
        var chunksById = indexById(chunks, Chunk::id);
        return vectors.stream()
            .map(vector -> {
                var chunk = chunksById.get(vector.id());
                if (chunk == null) {
                    throw new IllegalArgumentException("missing chunk for vector id: " + vector.id());
                }
                return new HybridVectorStore.EnrichedVectorRecord(
                    vector.id(),
                    vector.vector(),
                    chunk.text(),
                    chunkKeywords(chunk)
                );
            })
            .toList();
    }

    public static List<HybridVectorStore.EnrichedVectorRecord> entityPayloads(
        List<Entity> entities,
        List<VectorStore.VectorRecord> vectors
    ) {
        var entitiesById = indexById(entities, Entity::id);
        return vectors.stream()
            .map(vector -> {
                var entity = entitiesById.get(vector.id());
                if (entity == null) {
                    throw new IllegalArgumentException("missing entity for vector id: " + vector.id());
                }
                return new HybridVectorStore.EnrichedVectorRecord(
                    vector.id(),
                    vector.vector(),
                    entitySummary(entity),
                    entityKeywords(entity)
                );
            })
            .toList();
    }

    public static List<HybridVectorStore.EnrichedVectorRecord> relationPayloads(
        List<Relation> relations,
        List<VectorStore.VectorRecord> vectors
    ) {
        var relationsById = indexById(relations, Relation::id);
        return vectors.stream()
            .map(vector -> {
                var relation = relationsById.get(vector.id());
                if (relation == null) {
                    throw new IllegalArgumentException("missing relation for vector id: " + vector.id());
                }
                return new HybridVectorStore.EnrichedVectorRecord(
                    vector.id(),
                    vector.vector(),
                    relationSummary(relation),
                    relationKeywords(relation),
                    relation.srcId(),
                    relation.tgtId(),
                    relation.filePath()
                );
            })
            .toList();
    }

    private static List<String> chunkKeywords(Chunk chunk) {
        return normalizedKeywords(List.of(
            chunk.documentId(),
            chunk.metadata().getOrDefault(ParentChildChunkBuilder.METADATA_PARENT_CHUNK_ID, ""),
            chunk.metadata().getOrDefault("source", "")
        ));
    }

    private static List<String> entityKeywords(Entity entity) {
        var values = new java.util.ArrayList<String>();
        values.add(entity.name());
        values.add(entity.type());
        values.addAll(entity.aliases());
        return normalizedKeywords(values);
    }

    private static List<String> relationKeywords(Relation relation) {
        return normalizedKeywords(List.of(
            relation.srcId(),
            relation.keywords(),
            relation.tgtId(),
            relation.filePath()
        ));
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
            relation.srcId(),
            relation.keywords(),
            relation.tgtId(),
            relation.description()
        );
    }

    private static List<String> normalizedKeywords(List<String> values) {
        var normalized = new LinkedHashSet<String>();
        for (var value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.strip());
        }
        return List.copyOf(normalized);
    }

    private static <T> Map<String, T> indexById(List<T> values, java.util.function.Function<T, String> idExtractor) {
        var indexed = new LinkedHashMap<String, T>();
        for (var value : Objects.requireNonNull(values, "values")) {
            indexed.put(idExtractor.apply(value), value);
        }
        return Map.copyOf(indexed);
    }
}
