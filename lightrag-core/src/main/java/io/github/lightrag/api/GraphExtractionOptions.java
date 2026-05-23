package io.github.lightrag.api;

import io.github.lightrag.indexing.KnowledgeExtractor;

import java.util.List;
import java.util.Objects;

public record GraphExtractionOptions(
    Boolean enabled,
    Integer chunkExtractParallelism,
    Integer entityExtractMaxGleaning,
    Integer maxExtractInputTokens,
    String language,
    List<String> entityTypes,
    List<String> relationTypes,
    List<GraphExtractionExample> examples
) {
    public GraphExtractionOptions {
        if (chunkExtractParallelism != null && chunkExtractParallelism <= 0) {
            throw new IllegalArgumentException("chunkExtractParallelism must be positive");
        }
        if (entityExtractMaxGleaning != null && entityExtractMaxGleaning < 0) {
            throw new IllegalArgumentException("entityExtractMaxGleaning must not be negative");
        }
        if (maxExtractInputTokens != null && maxExtractInputTokens <= 0) {
            throw new IllegalArgumentException("maxExtractInputTokens must be positive");
        }
        if (language != null) {
            language = requireNonBlank(language, "language");
        }
        if (entityTypes != null) {
            entityTypes = List.copyOf(entityTypes).stream()
                .map(type -> requireNonBlank(type, "entityTypes entry"))
                .toList();
            if (entityTypes.isEmpty()) {
                throw new IllegalArgumentException("entityTypes must not be empty");
            }
        }
        if (relationTypes != null) {
            relationTypes = List.copyOf(relationTypes).stream()
                .map(type -> requireNonBlank(type, "relationTypes entry"))
                .toList();
        }
        if (examples != null) {
            examples = List.copyOf(examples);
        }
    }

    public static GraphExtractionOptions defaults() {
        return builder()
            .enabled(true)
            .chunkExtractParallelism(2)
            .entityExtractMaxGleaning(KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING)
            .maxExtractInputTokens(KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS)
            .language(KnowledgeExtractor.DEFAULT_LANGUAGE)
            .entityTypes(KnowledgeExtractor.DEFAULT_ENTITY_TYPES)
            .relationTypes(List.of())
            .examples(List.of())
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    GraphExtractionOptions mergeOver(GraphExtractionOptions fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return new GraphExtractionOptions(
            enabled != null ? enabled : fallback.enabled(),
            chunkExtractParallelism != null ? chunkExtractParallelism : fallback.chunkExtractParallelism(),
            entityExtractMaxGleaning != null ? entityExtractMaxGleaning : fallback.entityExtractMaxGleaning(),
            maxExtractInputTokens != null ? maxExtractInputTokens : fallback.maxExtractInputTokens(),
            language != null ? language : fallback.language(),
            entityTypes != null ? entityTypes : fallback.entityTypes(),
            relationTypes != null ? relationTypes : fallback.relationTypes(),
            examples != null ? examples : fallback.examples()
        );
    }

    boolean resolvedEnabled() {
        return enabled == null || enabled;
    }

    int resolvedChunkExtractParallelism() {
        return chunkExtractParallelism == null ? 2 : chunkExtractParallelism;
    }

    int resolvedEntityExtractMaxGleaning() {
        return entityExtractMaxGleaning == null
            ? KnowledgeExtractor.DEFAULT_ENTITY_EXTRACT_MAX_GLEANING
            : entityExtractMaxGleaning;
    }

    int resolvedMaxExtractInputTokens() {
        return maxExtractInputTokens == null
            ? KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS
            : maxExtractInputTokens;
    }

    String resolvedLanguage() {
        return language == null ? KnowledgeExtractor.DEFAULT_LANGUAGE : language;
    }

    List<String> resolvedEntityTypes() {
        return entityTypes == null ? KnowledgeExtractor.DEFAULT_ENTITY_TYPES : entityTypes;
    }

    List<String> resolvedRelationTypes() {
        return relationTypes == null ? List.of() : relationTypes;
    }

    List<GraphExtractionExample> resolvedExamples() {
        return examples == null ? List.of() : examples;
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        var normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public static final class Builder {
        private Boolean enabled;
        private Integer chunkExtractParallelism;
        private Integer entityExtractMaxGleaning;
        private Integer maxExtractInputTokens;
        private String language;
        private List<String> entityTypes;
        private List<String> relationTypes;
        private List<GraphExtractionExample> examples;

        public Builder enabled(Boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder chunkExtractParallelism(Integer chunkExtractParallelism) {
            this.chunkExtractParallelism = chunkExtractParallelism;
            return this;
        }

        public Builder entityExtractMaxGleaning(Integer entityExtractMaxGleaning) {
            this.entityExtractMaxGleaning = entityExtractMaxGleaning;
            return this;
        }

        public Builder maxExtractInputTokens(Integer maxExtractInputTokens) {
            this.maxExtractInputTokens = maxExtractInputTokens;
            return this;
        }

        public Builder language(String language) {
            this.language = language;
            return this;
        }

        public Builder entityTypes(List<String> entityTypes) {
            this.entityTypes = entityTypes;
            return this;
        }

        public Builder relationTypes(List<String> relationTypes) {
            this.relationTypes = relationTypes;
            return this;
        }

        public Builder examples(List<GraphExtractionExample> examples) {
            this.examples = examples;
            return this;
        }

        public GraphExtractionOptions build() {
            return new GraphExtractionOptions(
                enabled,
                chunkExtractParallelism,
                entityExtractMaxGleaning,
                maxExtractInputTokens,
                language,
                entityTypes,
                relationTypes,
                examples
            );
        }
    }
}
