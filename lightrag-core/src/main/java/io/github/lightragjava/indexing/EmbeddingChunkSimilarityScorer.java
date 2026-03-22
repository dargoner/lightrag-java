package io.github.lightragjava.indexing;

import io.github.lightragjava.types.Chunk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class EmbeddingChunkSimilarityScorer implements ChunkSimilarityScorer {
    private final EmbeddingBatcher embeddingBatcher;

    EmbeddingChunkSimilarityScorer(EmbeddingBatcher embeddingBatcher) {
        this.embeddingBatcher = Objects.requireNonNull(embeddingBatcher, "embeddingBatcher");
    }

    @Override
    public SemanticSimilarity similarityFor(List<Chunk> chunks) {
        var sourceChunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
        if (sourceChunks.isEmpty()) {
            return (left, right) -> 0.0d;
        }
        var texts = sourceChunks.stream().map(Chunk::text).toList();
        var embeddings = embeddingBatcher.embedAll(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("Embedding size mismatch: expected " + texts.size() + " but got " + embeddings.size());
        }
        var vectorsByText = new LinkedHashMap<String, List<Double>>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            vectorsByText.put(texts.get(index), embeddings.get(index));
        }
        // 只对原始 chunk 文本计算向量相似度
        return (left, right) -> cosineSimilarity(vectorsByText.get(left), vectorsByText.get(right));
    }

    private static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null) {
            throw new IllegalStateException("Missing embedding for requested text");
        }
        if (left.size() != right.size()) {
            throw new IllegalStateException("Embedding dimensions do not match");
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            var leftValue = left.get(index);
            var rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
