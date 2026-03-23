package io.github.lightragjava.indexing;

public record SmartChunkerConfig(
    int targetTokens,
    int maxTokens,
    int overlapTokens,
    boolean adaptiveChunkingEnabled,
    double adaptiveMinTargetRatio,
    double adaptiveMaxTargetRatio,
    double adaptiveOverlapRatio,
    boolean semanticMergeEnabled,
    double semanticMergeThreshold
) {
    private static final int DEFAULT_TARGET_TOKENS = 800;
    private static final int DEFAULT_MAX_TOKENS = 1_200;
    private static final int DEFAULT_OVERLAP_TOKENS = 100;
    private static final boolean DEFAULT_ADAPTIVE_CHUNKING_ENABLED = true;
    private static final double DEFAULT_ADAPTIVE_MIN_TARGET_RATIO = 0.70d;
    private static final double DEFAULT_ADAPTIVE_MAX_TARGET_RATIO = 1.35d;
    private static final double DEFAULT_ADAPTIVE_OVERLAP_RATIO = 0.12d;
    private static final boolean DEFAULT_SEMANTIC_MERGE_ENABLED = false;
    private static final double DEFAULT_SEMANTIC_MERGE_THRESHOLD = 0.80d;

    public SmartChunkerConfig {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("targetTokens must be positive");
        }
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        if (targetTokens > maxTokens) {
            throw new IllegalArgumentException("targetTokens must be smaller than or equal to maxTokens");
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens must be non-negative");
        }
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be smaller than maxTokens");
        }
        if (adaptiveMinTargetRatio <= 0.0d || adaptiveMinTargetRatio > 1.0d) {
            throw new IllegalArgumentException("adaptiveMinTargetRatio must be between 0.0 and 1.0");
        }
        if (adaptiveMaxTargetRatio < 1.0d) {
            throw new IllegalArgumentException("adaptiveMaxTargetRatio must be greater than or equal to 1.0");
        }
        if (adaptiveMinTargetRatio > adaptiveMaxTargetRatio) {
            throw new IllegalArgumentException("adaptiveMinTargetRatio must not exceed adaptiveMaxTargetRatio");
        }
        if (adaptiveOverlapRatio < 0.0d) {
            throw new IllegalArgumentException("adaptiveOverlapRatio must be non-negative");
        }
        if (semanticMergeThreshold < 0.0d || semanticMergeThreshold > 1.0d) {
            throw new IllegalArgumentException("semanticMergeThreshold must be between 0.0 and 1.0");
        }
    }

    public static SmartChunkerConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int targetTokens = DEFAULT_TARGET_TOKENS;
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private int overlapTokens = DEFAULT_OVERLAP_TOKENS;
        private boolean adaptiveChunkingEnabled = DEFAULT_ADAPTIVE_CHUNKING_ENABLED;
        private double adaptiveMinTargetRatio = DEFAULT_ADAPTIVE_MIN_TARGET_RATIO;
        private double adaptiveMaxTargetRatio = DEFAULT_ADAPTIVE_MAX_TARGET_RATIO;
        private double adaptiveOverlapRatio = DEFAULT_ADAPTIVE_OVERLAP_RATIO;
        private boolean semanticMergeEnabled = DEFAULT_SEMANTIC_MERGE_ENABLED;
        private double semanticMergeThreshold = DEFAULT_SEMANTIC_MERGE_THRESHOLD;

        private Builder() {
        }

        public Builder targetTokens(int targetTokens) {
            this.targetTokens = targetTokens;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder overlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
            return this;
        }

        public Builder adaptiveChunkingEnabled(boolean adaptiveChunkingEnabled) {
            this.adaptiveChunkingEnabled = adaptiveChunkingEnabled;
            return this;
        }

        public Builder adaptiveMinTargetRatio(double adaptiveMinTargetRatio) {
            this.adaptiveMinTargetRatio = adaptiveMinTargetRatio;
            return this;
        }

        public Builder adaptiveMaxTargetRatio(double adaptiveMaxTargetRatio) {
            this.adaptiveMaxTargetRatio = adaptiveMaxTargetRatio;
            return this;
        }

        public Builder adaptiveOverlapRatio(double adaptiveOverlapRatio) {
            this.adaptiveOverlapRatio = adaptiveOverlapRatio;
            return this;
        }

        public Builder semanticMergeEnabled(boolean semanticMergeEnabled) {
            this.semanticMergeEnabled = semanticMergeEnabled;
            return this;
        }

        public Builder semanticMergeThreshold(double semanticMergeThreshold) {
            this.semanticMergeThreshold = semanticMergeThreshold;
            return this;
        }

        public SmartChunkerConfig build() {
            return new SmartChunkerConfig(
                targetTokens,
                maxTokens,
                overlapTokens,
                adaptiveChunkingEnabled,
                adaptiveMinTargetRatio,
                adaptiveMaxTargetRatio,
                adaptiveOverlapRatio,
                semanticMergeEnabled,
                semanticMergeThreshold
            );
        }
    }
}
