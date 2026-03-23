package io.github.lightragjava.indexing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveChunkSizingPolicyTest {
    @Test
    void builderSupportsAdaptiveChunkingDefaults() {
        var config = SmartChunkerConfig.defaults();

        assertThat(config.adaptiveChunkingEnabled()).isTrue();
        assertThat(config.adaptiveMinTargetRatio()).isEqualTo(0.70d);
        assertThat(config.adaptiveMaxTargetRatio()).isEqualTo(1.35d);
        assertThat(config.adaptiveOverlapRatio()).isEqualTo(0.12d);
    }

    @Test
    void policyExpandsStableLongParagraphsForGenericDocuments() {
        var policy = new AdaptiveChunkSizingPolicy();
        var base = SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .build();

        var sizing = policy.resolve(
            base,
            DocumentType.GENERIC,
            StructuredBlock.Type.PARAGRAPH,
            false,
            8,
            120
        );

        assertThat(sizing.targetTokens()).isGreaterThan(base.targetTokens());
        assertThat(sizing.maxTokens()).isGreaterThan(base.maxTokens());
        assertThat(sizing.overlapTokens()).isGreaterThanOrEqualTo(base.overlapTokens());
    }

    @Test
    void policyShrinksHeadingTransitionParagraphs() {
        var policy = new AdaptiveChunkSizingPolicy();
        var base = SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .build();

        var sizing = policy.resolve(
            base,
            DocumentType.GENERIC,
            StructuredBlock.Type.PARAGRAPH,
            true,
            2,
            32
        );

        assertThat(sizing.targetTokens()).isLessThan(base.targetTokens());
        assertThat(sizing.maxTokens()).isLessThanOrEqualTo(base.maxTokens());
    }

    @Test
    void policyKeepsNonParagraphBlocksAtBaseSizing() {
        var policy = new AdaptiveChunkSizingPolicy();
        var base = SmartChunkerConfig.builder()
            .targetTokens(800)
            .maxTokens(1_200)
            .overlapTokens(100)
            .build();

        var sizing = policy.resolve(
            base,
            DocumentType.GENERIC,
            StructuredBlock.Type.TABLE,
            false,
            10,
            140
        );

        assertThat(sizing.targetTokens()).isEqualTo(base.targetTokens());
        assertThat(sizing.maxTokens()).isEqualTo(base.maxTokens());
        assertThat(sizing.overlapTokens()).isEqualTo(base.overlapTokens());
    }
}
