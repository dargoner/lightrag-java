package io.github.lightrag.indexing.refinement;

public record ExtractionRefinementOptions(
    boolean enabled,
    boolean allowDeterministicAttributionFallback,
    int maxWindowChunks,
    int maxWindowTokens,
    int maxRefinementPerDocument,
    int minPrescreenSignals,
    int minQualitySignals
) {
    private static final int DEFAULT_MAX_WINDOW_CHUNKS = 3;
    private static final int DEFAULT_MAX_WINDOW_TOKENS = 1_200;
    private static final int DEFAULT_MAX_REFINEMENT_PER_DOCUMENT = 1;
    private static final int DEFAULT_MIN_PRESCREEN_SIGNALS = 1;
    private static final int DEFAULT_MIN_QUALITY_SIGNALS = 1;

    public ExtractionRefinementOptions {
        if (maxWindowChunks <= 0) {
            throw new IllegalArgumentException("maxWindowChunks must be positive");
        }
        if (maxWindowTokens <= 0) {
            throw new IllegalArgumentException("maxWindowTokens must be positive");
        }
        if (maxRefinementPerDocument <= 0) {
            throw new IllegalArgumentException("maxRefinementPerDocument must be positive");
        }
        if (minPrescreenSignals <= 0) {
            throw new IllegalArgumentException("minPrescreenSignals must be positive");
        }
        if (minQualitySignals <= 0) {
            throw new IllegalArgumentException("minQualitySignals must be positive");
        }
    }

    public static ExtractionRefinementOptions disabled() {
        return new ExtractionRefinementOptions(
            false,
            false,
            DEFAULT_MAX_WINDOW_CHUNKS,
            DEFAULT_MAX_WINDOW_TOKENS,
            DEFAULT_MAX_REFINEMENT_PER_DOCUMENT,
            DEFAULT_MIN_PRESCREEN_SIGNALS,
            DEFAULT_MIN_QUALITY_SIGNALS
        );
    }
}
