package io.github.lightragjava.indexing;

import io.github.lightragjava.model.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class EmbeddingBatcher {
    private final EmbeddingModel embeddingModel;
    private final int embeddingBatchSize;

    EmbeddingBatcher(EmbeddingModel embeddingModel, int embeddingBatchSize) {
        this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel");
        this.embeddingBatchSize = embeddingBatchSize <= 0 ? Integer.MAX_VALUE : embeddingBatchSize;
    }

    List<List<Double>> embedAll(List<String> texts) {
        var sources = List.copyOf(Objects.requireNonNull(texts, "texts"));
        if (sources.isEmpty()) {
            return List.of();
        }
        if (embeddingBatchSize >= sources.size()) {
            return validateBatchEmbeddings(sources, embeddingModel.embedAll(sources));
        }
        // 按批次调用 embedding，避免单次请求过大
        var embeddings = new ArrayList<List<Double>>(sources.size());
        for (int start = 0; start < sources.size(); start += embeddingBatchSize) {
            var end = Math.min(sources.size(), start + embeddingBatchSize);
            var batchTexts = sources.subList(start, end);
            embeddings.addAll(validateBatchEmbeddings(batchTexts, embeddingModel.embedAll(batchTexts)));
        }
        return List.copyOf(embeddings);
    }

    private static List<List<Double>> validateBatchEmbeddings(List<String> batchTexts, List<List<Double>> batchEmbeddings) {
        if (batchEmbeddings.size() != batchTexts.size()) {
            throw new IllegalStateException(
                "Embedding size mismatch for batch: expected " + batchTexts.size() + " but got " + batchEmbeddings.size()
            );
        }
        return batchEmbeddings;
    }
}
