package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SemanticChunkRefiner {
    private final SemanticSimilarity semanticSimilarity;

    public SemanticChunkRefiner() {
        this(defaultSimilarity());
    }

    public SemanticChunkRefiner(SemanticSimilarity semanticSimilarity) {
        this.semanticSimilarity = Objects.requireNonNull(semanticSimilarity, "semanticSimilarity");
    }

    public List<Chunk> refine(String documentId, List<Chunk> chunks, int maxTokens, double threshold) {
        return refine(documentId, chunks, maxTokens, threshold, semanticSimilarity, MergeMode.GREEDY_CASCADING);
    }

    List<Chunk> refine(
        String documentId,
        List<Chunk> chunks,
        int maxTokens,
        double threshold,
        SemanticSimilarity similarity,
        MergeMode mergeMode
    ) {
        Objects.requireNonNull(documentId, "documentId");
        var sourceChunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        Objects.requireNonNull(similarity, "similarity");
        Objects.requireNonNull(mergeMode, "mergeMode");

        if (sourceChunks.isEmpty()) {
            return List.of();
        }

        List<MergedChunk> merged;
        if (sourceChunks.size() == 1) {
            if (mergeMode == MergeMode.PAIRWISE_SINGLE_PASS) {
                validateEmbeddingMetadata(sourceChunks);
            }
            merged = List.of(MergedChunk.from(sourceChunks.get(0)));
        } else {
            merged = switch (mergeMode) {
                case GREEDY_CASCADING -> mergeGreedy(sourceChunks, maxTokens, threshold, similarity);
                case PAIRWISE_SINGLE_PASS -> mergePairwiseSinglePass(sourceChunks, maxTokens, threshold, similarity);
            };
        }

        var normalized = new ArrayList<Chunk>(merged.size());
        for (int index = 0; index < merged.size(); index++) {
            var chunkId = documentId + ":" + index;
            var metadata = new LinkedHashMap<>(merged.get(index).metadata());
            metadata.put(SmartChunkMetadata.PREV_CHUNK_ID, index == 0 ? "" : documentId + ":" + (index - 1));
            metadata.put(SmartChunkMetadata.NEXT_CHUNK_ID, index + 1 >= merged.size() ? "" : documentId + ":" + (index + 1));
            normalized.add(new Chunk(
                chunkId,
                documentId,
                merged.get(index).text(),
                merged.get(index).text().codePointCount(0, merged.get(index).text().length()),
                index,
                metadata
            ));
        }
        return List.copyOf(normalized);
    }

    public static SemanticSimilarity defaultSimilarity() {
        return (left, right) -> {
            var leftTokens = tokens(left);
            var rightTokens = tokens(right);
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
                return 0.0d;
            }
            int common = 0;
            for (var token : leftTokens) {
                if (rightTokens.contains(token)) {
                    common++;
                }
            }
            return (double) common / (double) Math.min(leftTokens.size(), rightTokens.size());
        };
    }

    private static List<MergedChunk> mergeGreedy(
        List<Chunk> sourceChunks,
        int maxTokens,
        double threshold,
        SemanticSimilarity similarity
    ) {
        var merged = new ArrayList<MergedChunk>();
        var current = MergedChunk.from(sourceChunks.get(0));
        for (int index = 1; index < sourceChunks.size(); index++) {
            var next = sourceChunks.get(index);
            if (shouldMerge(current, next, maxTokens, threshold, similarity)) {
                current = current.merge(next);
            } else {
                merged.add(current);
                current = MergedChunk.from(next);
            }
        }
        merged.add(current);
        return merged;
    }

    private static List<MergedChunk> mergePairwiseSinglePass(
        List<Chunk> sourceChunks,
        int maxTokens,
        double threshold,
        SemanticSimilarity similarity
    ) {
        validateEmbeddingMetadata(sourceChunks);
        var merged = new ArrayList<MergedChunk>();
        int index = 0;
        while (index < sourceChunks.size()) {
            var current = sourceChunks.get(index);
            if (index + 1 < sourceChunks.size()) {
                var next = sourceChunks.get(index + 1);
                if (shouldMergeEmbedding(current, next, maxTokens, threshold, similarity)) {
                    // 单轮相邻合并：合并后不再与后续 chunk 复评
                    merged.add(MergedChunk.from(current).merge(next));
                    index += 2;
                    continue;
                }
            }
            merged.add(MergedChunk.from(current));
            index++;
        }
        return merged;
    }

    private static boolean shouldMerge(MergedChunk current, Chunk next, int maxTokens, double threshold, SemanticSimilarity similarity) {
        var combinedText = current.text() + "\n" + next.text();
        if (combinedText.codePointCount(0, combinedText.length()) > maxTokens) {
            return false;
        }
        return similarity.score(current.text(), next.text()) >= threshold;
    }

    private static boolean shouldMergeEmbedding(Chunk left, Chunk right, int maxTokens, double threshold, SemanticSimilarity similarity) {
        var leftSection = requireMetadata(left.metadata(), SmartChunkMetadata.SECTION_PATH);
        var rightSection = requireMetadata(right.metadata(), SmartChunkMetadata.SECTION_PATH);
        if (!leftSection.equals(rightSection)) {
            return false;
        }
        var leftType = requireMetadata(left.metadata(), SmartChunkMetadata.CONTENT_TYPE);
        var rightType = requireMetadata(right.metadata(), SmartChunkMetadata.CONTENT_TYPE);
        if (!leftType.equals(rightType)) {
            return false;
        }
        if ("table".equalsIgnoreCase(leftType)) {
            return false;
        }
        var combinedText = left.text() + "\n" + right.text();
        if (combinedText.codePointCount(0, combinedText.length()) > maxTokens) {
            return false;
        }
        return similarity.score(left.text(), right.text()) >= threshold;
    }

    private static void validateEmbeddingMetadata(List<Chunk> chunks) {
        for (var chunk : chunks) {
            requireMetadata(chunk.metadata(), SmartChunkMetadata.SECTION_PATH);
            requireMetadata(chunk.metadata(), SmartChunkMetadata.CONTENT_TYPE);
        }
    }

    private static String requireMetadata(Map<String, String> metadata, String key) {
        var value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required SmartChunker metadata: " + key);
        }
        return value.strip();
    }

    private static Set<String> tokens(String text) {
        var result = new LinkedHashSet<String>();
        for (var token : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (!token.isBlank()) {
                result.add(token);
            }
        }
        return result;
    }

    private record MergedChunk(String text, Map<String, String> metadata) {
        private static MergedChunk from(Chunk chunk) {
            return new MergedChunk(chunk.text(), new LinkedHashMap<>(chunk.metadata()));
        }

        private MergedChunk merge(Chunk other) {
            var mergedMetadata = new LinkedHashMap<>(metadata);
            mergedMetadata.put(SmartChunkMetadata.SOURCE_BLOCK_IDS, mergeSourceBlockIds(
                metadata.getOrDefault(SmartChunkMetadata.SOURCE_BLOCK_IDS, ""),
                other.metadata().getOrDefault(SmartChunkMetadata.SOURCE_BLOCK_IDS, "")
            ));
            return new MergedChunk(text + "\n" + other.text(), mergedMetadata);
        }

        private static String mergeSourceBlockIds(String left, String right) {
            var ids = new LinkedHashSet<String>();
            addIds(ids, left);
            addIds(ids, right);
            return String.join(",", ids);
        }

        private static void addIds(Set<String> ids, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            for (var token : value.split(",")) {
                var normalized = token.strip();
                if (!normalized.isEmpty()) {
                    ids.add(normalized);
                }
            }
        }
    }
}

enum MergeMode {
    GREEDY_CASCADING,
    PAIRWISE_SINGLE_PASS
}
