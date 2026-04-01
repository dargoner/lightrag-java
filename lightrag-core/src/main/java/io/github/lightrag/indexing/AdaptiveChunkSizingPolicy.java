package io.github.lightrag.indexing;

import java.util.Objects;

final class AdaptiveChunkSizingPolicy {
    AdaptiveSizing resolve(
        SmartChunkerConfig base,
        DocumentType documentType,
        StructuredBlock.Type blockType,
        boolean headingTransition,
        int sentenceCount,
        int averageSentenceTokens
    ) {
        var config = Objects.requireNonNull(base, "base");
        Objects.requireNonNull(documentType, "documentType");
        Objects.requireNonNull(blockType, "blockType");
        if (!config.adaptiveChunkingEnabled() || blockType != StructuredBlock.Type.PARAGRAPH) {
            return AdaptiveSizing.fromConfig(config);
        }

        double ratio = 1.0d;
        if (headingTransition) {
            ratio = Math.min(ratio, 0.75d);
        }
        if (sentenceCount <= 2 || averageSentenceTokens <= 48) {
            ratio = Math.min(ratio, 0.90d);
        }
        if (sentenceCount >= 6 && averageSentenceTokens >= 60) {
            ratio = Math.max(ratio, expansionRatio(documentType));
        }

        var minRatio = config.adaptiveMinTargetRatio();
        var maxRatio = Math.min(config.adaptiveMaxTargetRatio(), maxExpansionRatio(documentType));
        ratio = clamp(ratio, minRatio, maxRatio);

        int effectiveTarget = clampInt(
            (int) Math.round(config.targetTokens() * ratio),
            (int) Math.round(config.targetTokens() * minRatio),
            (int) Math.round(config.targetTokens() * maxRatio)
        );

        double maxTokenRatio = ratio >= 1.0d
            ? Math.min(maxRatio, 1.0d + ((ratio - 1.0d) * 0.90d))
            : headingTransition
            ? Math.max(minRatio, ratio + 0.02d)
            : Math.max(minRatio, ratio + 0.10d);
        int effectiveMax = clampInt(
            (int) Math.round(config.maxTokens() * maxTokenRatio),
            effectiveTarget,
            (int) Math.round(config.maxTokens() * maxRatio)
        );

        int adaptiveOverlap = (int) Math.round(effectiveTarget * config.adaptiveOverlapRatio());
        int effectiveOverlap = ratio >= 1.0d
            ? Math.max(config.overlapTokens(), adaptiveOverlap)
            : Math.min(config.overlapTokens(), adaptiveOverlap);
        effectiveOverlap = clampInt(effectiveOverlap, 0, Math.max(0, effectiveMax - 1));

        return new AdaptiveSizing(effectiveTarget, effectiveMax, effectiveOverlap);
    }

    private static double expansionRatio(DocumentType documentType) {
        return switch (documentType) {
            case GENERIC, BOOK -> 1.35d;
            case LAW, QA -> 1.05d;
        };
    }

    private static double maxExpansionRatio(DocumentType documentType) {
        return switch (documentType) {
            case GENERIC, BOOK -> 1.35d;
            case LAW, QA -> 1.08d;
        };
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    record AdaptiveSizing(int targetTokens, int maxTokens, int overlapTokens) {
        AdaptiveSizing {
            if (targetTokens <= 0) {
                throw new IllegalArgumentException("targetTokens must be positive");
            }
            if (maxTokens < targetTokens) {
                throw new IllegalArgumentException("maxTokens must be greater than or equal to targetTokens");
            }
            if (overlapTokens < 0 || overlapTokens >= maxTokens) {
                throw new IllegalArgumentException("overlapTokens must be between 0 and maxTokens - 1");
            }
        }

        static AdaptiveSizing fromConfig(SmartChunkerConfig config) {
            return new AdaptiveSizing(
                config.targetTokens(),
                config.maxTokens(),
                config.overlapTokens()
            );
        }
    }
}
