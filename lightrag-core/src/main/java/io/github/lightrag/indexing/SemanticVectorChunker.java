package io.github.lightrag.indexing;

import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class SemanticVectorChunker {
    public static final String DEFAULT_SENTENCE_SPLIT_REGEX = "(?<=[.?!])\\s+|(?<=[\u3002\uff1f\uff01])";
    private static final String FALLBACK_REASON = "embedding model unavailable; upstream V fallback to R applied";

    private final int chunkSize;
    private final EmbeddingModel embeddingModel;
    private final int embeddingBatchSize;
    private final String breakpointThresholdType;
    private final Double breakpointThresholdAmount;
    private final int bufferSize;
    private final Pattern sentenceSplitPattern;
    private final ChunkTextTokenizer tokenizer;
    private final RecursiveCharacterChunker oversizedChunkFallback;
    private final RecursiveCharacterChunker missingEmbeddingFallback;

    public SemanticVectorChunker(int chunkSize) {
        this(chunkSize, null, Integer.MAX_VALUE);
    }

    public SemanticVectorChunker(int chunkSize, EmbeddingModel embeddingModel, int embeddingBatchSize) {
        this(
            chunkSize,
            embeddingModel,
            embeddingBatchSize,
            "percentile",
            null,
            1,
            DEFAULT_SENTENCE_SPLIT_REGEX,
            UnicodeCodePointChunkTextTokenizer.INSTANCE
        );
    }

    public SemanticVectorChunker(
        int chunkSize,
        EmbeddingModel embeddingModel,
        int embeddingBatchSize,
        String breakpointThresholdType,
        Double breakpointThresholdAmount,
        int bufferSize,
        String sentenceSplitRegex,
        ChunkTextTokenizer tokenizer
    ) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (bufferSize < 0) {
            throw new IllegalArgumentException("bufferSize must be non-negative");
        }
        this.chunkSize = chunkSize;
        this.embeddingModel = embeddingModel;
        this.embeddingBatchSize = embeddingBatchSize <= 0 ? Integer.MAX_VALUE : embeddingBatchSize;
        this.breakpointThresholdType = normalizeThresholdType(breakpointThresholdType);
        this.breakpointThresholdAmount = breakpointThresholdAmount;
        this.bufferSize = bufferSize;
        this.sentenceSplitPattern = Pattern.compile(
            sentenceSplitRegex == null || sentenceSplitRegex.isBlank()
                ? DEFAULT_SENTENCE_SPLIT_REGEX
                : sentenceSplitRegex
        );
        this.tokenizer = Objects.requireNonNull(tokenizer, "tokenizer");
        this.oversizedChunkFallback = new RecursiveCharacterChunker(chunkSize, 0, this.tokenizer, RecursiveCharacterChunker.DEFAULT_SEPARATORS);
        this.missingEmbeddingFallback = new RecursiveCharacterChunker(chunkSize, 0, this.tokenizer, RecursiveCharacterChunker.DEFAULT_SEPARATORS);
    }

    public ChunkingResult chunk(ParsedDocument document) {
        var parsed = Objects.requireNonNull(document, "document");
        var source = new Document(parsed.documentId(), parsed.title(), parsed.plainText(), parsed.metadata());
        if (embeddingModel == null) {
            return new ChunkingResult(
                missingEmbeddingFallback.chunk(source),
                ChunkingMode.RECURSIVE,
                false,
                FALLBACK_REASON
            );
        }
        return new ChunkingResult(chunk(source), ChunkingMode.SEMANTIC_VECTOR, false, null);
    }

    public List<Chunk> chunk(Document document) {
        var source = Objects.requireNonNull(document, "document");
        if (source.content().isBlank()) {
            return List.of();
        }
        if (embeddingModel == null) {
            return missingEmbeddingFallback.chunk(source);
        }

        var sentences = splitSentences(source.content());
        if (sentences.isEmpty()) {
            return List.of();
        }
        var pieces = sentences.size() == 1 ? sentences : splitByEmbeddingBreakpoints(sentences);
        var chunkTexts = enforceChunkSize(pieces);
        var chunks = new ArrayList<Chunk>(chunkTexts.size());
        for (int order = 0; order < chunkTexts.size(); order++) {
            var text = chunkTexts.get(order);
            chunks.add(new Chunk(
                source.id() + ":" + order,
                source.id(),
                text,
                tokenizer.count(text),
                order,
                source.metadata()
            ));
        }
        return List.copyOf(chunks);
    }

    private List<String> splitByEmbeddingBreakpoints(List<String> sentences) {
        var combinedSentences = combinedSentences(sentences);
        var embeddings = new EmbeddingBatcher(embeddingModel, embeddingBatchSize).embedAll(combinedSentences);
        var distances = cosineDistances(embeddings);
        if (distances.isEmpty()) {
            return sentences;
        }
        var valuesForThreshold = "gradient".equals(breakpointThresholdType) ? gradient(distances) : distances;
        var threshold = calculateThreshold(valuesForThreshold);
        var pieces = new ArrayList<String>();
        var start = 0;
        for (int index = 0; index < distances.size(); index++) {
            if (valuesForThreshold.get(index) > threshold) {
                pieces.add(joinSentences(sentences.subList(start, index + 1)));
                start = index + 1;
            }
        }
        if (start < sentences.size()) {
            pieces.add(joinSentences(sentences.subList(start, sentences.size())));
        }
        return List.copyOf(pieces);
    }

    private List<String> combinedSentences(List<String> sentences) {
        var combined = new ArrayList<String>(sentences.size());
        for (int index = 0; index < sentences.size(); index++) {
            var start = Math.max(0, index - bufferSize);
            var end = Math.min(sentences.size(), index + bufferSize + 1);
            combined.add(joinSentences(sentences.subList(start, end)));
        }
        return List.copyOf(combined);
    }

    private List<String> splitSentences(String content) {
        var rawSentences = sentenceSplitPattern.split(content.strip());
        var sentences = new ArrayList<String>(rawSentences.length);
        for (var raw : rawSentences) {
            var sentence = raw.strip();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return List.copyOf(sentences);
    }

    private List<String> enforceChunkSize(List<String> pieces) {
        var chunkTexts = new ArrayList<String>();
        for (var piece : pieces) {
            var body = piece.strip();
            if (body.isBlank()) {
                continue;
            }
            if (tokenizer.count(body) <= chunkSize) {
                chunkTexts.add(body);
                continue;
            }
            var split = oversizedChunkFallback.chunk(new Document("semantic-vector-oversized", "", body, Map.of()));
            for (var chunk : split) {
                if (!chunk.text().isBlank()) {
                    chunkTexts.add(chunk.text());
                }
            }
        }
        return List.copyOf(chunkTexts);
    }

    private double calculateThreshold(List<Double> values) {
        if (values.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        return switch (breakpointThresholdType) {
            case "percentile", "gradient" -> percentile(values, breakpointThresholdAmount == null ? 95.0d : breakpointThresholdAmount);
            case "standard_deviation" -> mean(values) + (breakpointThresholdAmount == null ? 3.0d : breakpointThresholdAmount) * standardDeviation(values);
            case "interquartile" -> {
                var q1 = percentile(values, 25.0d);
                var q3 = percentile(values, 75.0d);
                yield q3 + (breakpointThresholdAmount == null ? 1.5d : breakpointThresholdAmount) * (q3 - q1);
            }
            default -> throw new IllegalStateException("unsupported threshold type: " + breakpointThresholdType);
        };
    }

    private static List<Double> cosineDistances(List<List<Double>> embeddings) {
        var distances = new ArrayList<Double>(Math.max(0, embeddings.size() - 1));
        for (int index = 0; index + 1 < embeddings.size(); index++) {
            distances.add(1.0d - cosineSimilarity(embeddings.get(index), embeddings.get(index + 1)));
        }
        return List.copyOf(distances);
    }

    private static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.size() != right.size()) {
            throw new IllegalStateException("embedding dimensions must match");
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int index = 0; index < left.size(); index++) {
            var l = left.get(index);
            var r = right.get(index);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static List<Double> gradient(List<Double> values) {
        if (values.size() <= 1) {
            return values;
        }
        var gradients = new ArrayList<Double>(values.size());
        for (int index = 0; index < values.size(); index++) {
            if (index == 0) {
                gradients.add(values.get(1) - values.get(0));
            } else if (index == values.size() - 1) {
                gradients.add(values.get(index) - values.get(index - 1));
            } else {
                gradients.add((values.get(index + 1) - values.get(index - 1)) / 2.0d);
            }
        }
        return List.copyOf(gradients);
    }

    private static double percentile(List<Double> values, double percentile) {
        var sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        var bounded = Math.max(0.0d, Math.min(100.0d, percentile));
        var rank = (bounded / 100.0d) * (sorted.size() - 1);
        var lower = (int) Math.floor(rank);
        var upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        var weight = rank - lower;
        return sorted.get(lower) * (1.0d - weight) + sorted.get(upper) * weight;
    }

    private static double mean(List<Double> values) {
        var sum = 0.0d;
        for (var value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    private static double standardDeviation(List<Double> values) {
        var mean = mean(values);
        var sum = 0.0d;
        for (var value : values) {
            var delta = value - mean;
            sum += delta * delta;
        }
        return Math.sqrt(sum / values.size());
    }

    private static String joinSentences(List<String> sentences) {
        return String.join(" ", sentences).strip();
    }

    private static String normalizeThresholdType(String type) {
        var normalized = type == null || type.isBlank() ? "percentile" : type.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "percentile", "standard_deviation", "interquartile", "gradient" -> normalized;
            default -> throw new IllegalArgumentException("unsupported semantic vector breakpoint threshold type: " + type);
        };
    }
}
