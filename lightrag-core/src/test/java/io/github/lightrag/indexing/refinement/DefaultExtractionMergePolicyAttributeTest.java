package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import io.github.lightrag.types.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExtractionMergePolicyAttributeTest {

    @Test
    void mergesEntityAttributesWithPatchValuesWinning() {
        var mergePolicy = new DefaultExtractionMergePolicy();
        var primary = primary(
            "chunk-1",
            List.of(new ExtractedEntity(
                "Alice", "Person", "primary", List.of("Al"),
                Map.of("age", 30L, "city", "X"))),
            List.of());
        var patch = new ChunkExtractionPatch(
            "chunk-1",
            List.of(new ExtractedEntity(
                "Alice", "Person", "patch", List.of("Alicia"),
                Map.of("age", 31L, "tags", List.of("t")))),
            List.of()
        );

        var merged = mergePolicy.merge(primary, List.of(patch));

        assertThat(merged.extraction().entities()).containsExactly(
            new ExtractedEntity(
                "Alice",
                "Person",
                "primary",
                List.of("Al", "Alicia"),
                Map.of("age", 31L, "city", "X", "tags", List.of("t"))));
    }

    @Test
    void mergesRelationAttributesWithPatchValuesWinning() {
        var mergePolicy = new DefaultExtractionMergePolicy();
        var primary = primary(
            "chunk-1",
            List.of(
                new ExtractedEntity("Alice", "Person", "", List.of()),
                new ExtractedEntity("Bob", "Person", "", List.of())),
            List.of(new ExtractedRelation(
                "Alice", "Bob", "works_with", "primary", 1.0d,
                Map.of("since", "2020-01-01", "confidence", 0.5d))));
        var patch = new ChunkExtractionPatch(
            "chunk-1",
            List.of(),
            List.of(new ExtractedRelation(
                "Alice", "Bob", "works_with", "patch", 1.0d,
                Map.of("confidence", 0.9d)))
        );

        var merged = mergePolicy.merge(primary, List.of(patch));

        assertThat(merged.extraction().relations()).containsExactly(
            new ExtractedRelation(
                "Alice", "Bob", "works_with", "primary", 1.0d,
                Map.of("since", "2020-01-01", "confidence", 0.9d)));
    }

    @Test
    void injectedEndpointPlaceholdersKeepEmptyAttributes() {
        var mergePolicy = new DefaultExtractionMergePolicy();
        var primary = primary("chunk-1", List.of(), List.of());
        var patch = new ChunkExtractionPatch(
            "chunk-1",
            List.of(),
            List.of(new ExtractedRelation(
                "Alice", "Bob", "works_with", "collaboration", 1.0d,
                Map.of("since", "2020-01-01")))
        );

        var merged = mergePolicy.merge(primary, List.of(patch));

        assertThat(merged.extraction().entities()).containsExactly(
            new ExtractedEntity("Alice", "", "", List.of(), Map.of()),
            new ExtractedEntity("Bob", "", "", List.of(), Map.of()));
    }

    private static PrimaryChunkExtraction primary(
        String chunkId,
        List<ExtractedEntity> entities,
        List<ExtractedRelation> relations
    ) {
        return new PrimaryChunkExtraction(
            new Chunk(chunkId, "doc-1", "text", 4, 0, Map.of()),
            new ExtractionResult(entities, relations, List.of())
        );
    }
}
