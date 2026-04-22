package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DefaultAttributionResolver implements AttributionResolver {
    private final boolean allowDeterministicFallback;

    public DefaultAttributionResolver(boolean allowDeterministicFallback) {
        this.allowDeterministicFallback = allowDeterministicFallback;
    }

    @Override
    public List<ChunkExtractionPatch> distribute(RefinedWindowExtraction refinedWindow, RefinementWindow window) {
        Objects.requireNonNull(refinedWindow, "refinedWindow");
        Objects.requireNonNull(window, "window");

        var patches = new LinkedHashMap<String, MutableChunkPatch>();
        for (var chunk : window.chunks()) {
            patches.put(chunk.id(), new MutableChunkPatch(chunk.id()));
        }

        for (var entityPatch : refinedWindow.entityPatches()) {
            distributeEntityPatch(entityPatch, patches);
        }
        for (var relationPatch : refinedWindow.relationPatches()) {
            distributeRelationPatch(relationPatch, patches, window);
        }

        return patches.values().stream()
            .filter(MutableChunkPatch::hasContent)
            .map(MutableChunkPatch::toPatch)
            .toList();
    }

    private void distributeEntityPatch(
        RefinedEntityPatch entityPatch,
        LinkedHashMap<String, MutableChunkPatch> patches
    ) {
        var targetChunkIds = entityPatch.supportingChunkIds();
        if (targetChunkIds.isEmpty() && !allowDeterministicFallback) {
            return;
        }
        for (var chunkId : targetChunkIds) {
            var patch = patches.get(chunkId);
            if (patch != null) {
                patch.addEntity(entityPatch.entity());
            }
        }
    }

    private void distributeRelationPatch(
        RefinedRelationPatch relationPatch,
        LinkedHashMap<String, MutableChunkPatch> patches,
        RefinementWindow window
    ) {
        var targetChunkIds = relationPatch.supportingChunkIds();
        if (targetChunkIds.isEmpty() || targetChunkIds.stream().noneMatch(patches::containsKey)) {
            if (!allowDeterministicFallback) {
                return;
            }
            targetChunkIds = fallbackRelationChunkIds(relationPatch.relation(), window);
        }
        for (var chunkId : targetChunkIds) {
            var patch = patches.get(chunkId);
            if (patch != null) {
                patch.addRelation(relationPatch.relation());
            }
        }
    }

    private static List<String> fallbackRelationChunkIds(ExtractedRelation relation, RefinementWindow window) {
        var normalizedSource = normalizeChunkText(relation.sourceEntityName());
        var normalizedTarget = normalizeChunkText(relation.targetEntityName());
        var normalizedDescription = normalizeChunkText(relation.description());
        var matchedChunkIds = new LinkedHashSet<String>();
        for (var chunk : window.chunks()) {
            var normalizedChunk = normalizeChunkText(chunk.text());
            if (!normalizedChunk.contains(normalizedSource) || !normalizedChunk.contains(normalizedTarget)) {
                continue;
            }
            if (!normalizedDescription.isEmpty() && !normalizedChunk.contains(normalizedDescription)) {
                continue;
            }
            matchedChunkIds.add(chunk.id());
        }
        return List.copyOf(matchedChunkIds);
    }

    private static final class MutableChunkPatch {
        private final String chunkId;
        private final LinkedHashMap<String, ExtractedEntity> entities = new LinkedHashMap<>();
        private final LinkedHashMap<String, ExtractedRelation> relations = new LinkedHashMap<>();

        private MutableChunkPatch(String chunkId) {
            this.chunkId = chunkId;
        }

        private void addEntity(ExtractedEntity entity) {
            entities.putIfAbsent(normalize(entity.name()), entity);
        }

        private void addRelation(ExtractedRelation relation) {
            relations.putIfAbsent(relationKey(relation), relation);
        }

        private boolean hasContent() {
            return !entities.isEmpty() || !relations.isEmpty();
        }

        private ChunkExtractionPatch toPatch() {
            return new ChunkExtractionPatch(chunkId, List.copyOf(entities.values()), List.copyOf(relations.values()));
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizeChunkText(String value) {
        return normalize(value).replaceAll("\\s+", " ");
    }

    private static String relationKey(ExtractedRelation relation) {
        var left = normalize(relation.sourceEntityName());
        var right = normalize(relation.targetEntityName());
        var first = left.compareTo(right) <= 0 ? left : right;
        var second = left.compareTo(right) <= 0 ? right : left;
        return first
            + "\u0000"
            + second
            + "\u0000"
            + canonicalKeywords(relation.keywords());
    }

    private static String canonicalKeywords(String value) {
        var keywords = new java.util.TreeSet<String>();
        for (var rawKeyword : Objects.requireNonNull(value, "value").split(",")) {
            var normalized = normalize(rawKeyword).replaceAll("[\\s_-]+", "_");
            if (!normalized.isEmpty()) {
                keywords.add(normalized);
            }
        }
        return String.join(", ", keywords);
    }
}
