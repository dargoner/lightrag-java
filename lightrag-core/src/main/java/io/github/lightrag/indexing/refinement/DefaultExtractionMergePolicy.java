package io.github.lightrag.indexing.refinement;

import io.github.lightrag.indexing.GraphAssembler;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DefaultExtractionMergePolicy implements ExtractionMergePolicy {
    @Override
    public GraphAssembler.ChunkExtraction merge(PrimaryChunkExtraction primary, List<ChunkExtractionPatch> patchesForChunk) {
        var resolvedPrimary = Objects.requireNonNull(primary, "primary");
        var resolvedPatches = List.copyOf(Objects.requireNonNull(patchesForChunk, "patchesForChunk"));

        var entities = new LinkedHashMap<String, ExtractedEntity>();
        resolvedPrimary.extraction().entities().forEach(entity -> entities.put(normalize(entity.name()), entity));
        var relations = new LinkedHashMap<String, ExtractedRelation>();
        resolvedPrimary.extraction().relations().forEach(relation -> relations.put(relationKey(relation), relation));

        for (var patch : resolvedPatches) {
            if (!resolvedPrimary.chunk().id().equals(patch.chunkId())) {
                continue;
            }
            for (var entity : patch.entities()) {
                entities.merge(normalize(entity.name()), entity, DefaultExtractionMergePolicy::mergeEntity);
            }
            for (var relation : patch.relations()) {
                relations.merge(relationKey(relation), relation, DefaultExtractionMergePolicy::mergeRelation);
                ensureEntityPresent(entities, relation.sourceEntityName());
                ensureEntityPresent(entities, relation.targetEntityName());
            }
        }

        return new GraphAssembler.ChunkExtraction(
            resolvedPrimary.chunk().id(),
            new ExtractionResult(
                List.copyOf(entities.values()),
                List.copyOf(relations.values()),
                resolvedPrimary.extraction().warnings()
            )
        );
    }

    private static void ensureEntityPresent(LinkedHashMap<String, ExtractedEntity> entities, String entityName) {
        entities.computeIfAbsent(normalize(entityName), ignored -> new ExtractedEntity(entityName, "", "", List.of()));
    }

    private static ExtractedEntity mergeEntity(ExtractedEntity left, ExtractedEntity right) {
        var aliases = new LinkedHashSet<String>();
        aliases.addAll(left.aliases());
        aliases.addAll(right.aliases());
        return new ExtractedEntity(
            left.name(),
            preferredText(left.type(), right.type()),
            longerText(left.description(), right.description()),
            List.copyOf(aliases)
        );
    }

    private static ExtractedRelation mergeRelation(ExtractedRelation left, ExtractedRelation right) {
        return new ExtractedRelation(
            left.sourceEntityName(),
            left.targetEntityName(),
            left.type(),
            longerText(left.description(), right.description()),
            Math.max(left.weight(), right.weight())
        );
    }

    private static String preferredText(String left, String right) {
        return left == null || left.isBlank() ? right : left;
    }

    private static String longerText(String left, String right) {
        return (right != null && right.strip().length() > (left == null ? 0 : left.strip().length())) ? right : left;
    }

    private static String relationKey(ExtractedRelation relation) {
        return normalize(relation.sourceEntityName())
            + "\u0000"
            + canonicalRelationType(relation.type())
            + "\u0000"
            + normalize(relation.targetEntityName());
    }

    private static String canonicalRelationType(String value) {
        return normalize(value).replaceAll("[\\s_-]+", "_");
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }
}
