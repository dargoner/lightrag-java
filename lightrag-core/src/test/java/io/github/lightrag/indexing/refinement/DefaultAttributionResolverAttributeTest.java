package io.github.lightrag.indexing.refinement;

import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.ExtractedEntity;
import io.github.lightrag.types.ExtractedRelation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAttributionResolverAttributeTest {

    @Test
    void mergesDuplicateEntityCandidatesInsteadOfDroppingTheirAttributes() {
        var resolver = new DefaultAttributionResolver(false);
        var window = new RefinementWindow(
            "doc-1",
            List.of(chunk("chunk-1", "Alice")),
            0,
            RefinementScope.ADJACENT,
            16
        );
        var refined = new RefinedWindowExtraction(
            List.of(
                new RefinedEntityPatch(
                    new ExtractedEntity(
                        "Alice", "Person", "first", List.of(), Map.of("age", 30L)),
                    List.of("chunk-1")),
                new RefinedEntityPatch(
                    new ExtractedEntity(
                        "Alice", "Person", "second", List.of(), Map.of("age", 31L, "city", "X")),
                    List.of("chunk-1"))
            ),
            List.of(),
            List.of(),
            true
        );

        var patches = resolver.distribute(refined, window);

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).entities()).containsExactly(
            new ExtractedEntity(
                "Alice",
                "Person",
                "first",
                List.of(),
                Map.of("age", 31L, "city", "X")));
    }

    @Test
    void mergesDuplicateRelationCandidatesInsteadOfDroppingTheirAttributes() {
        var resolver = new DefaultAttributionResolver(false);
        var window = new RefinementWindow(
            "doc-1",
            List.of(chunk("chunk-1", "Alice works with Bob")),
            0,
            RefinementScope.ADJACENT,
            16
        );
        var refined = new RefinedWindowExtraction(
            List.of(),
            List.of(
                new RefinedRelationPatch(
                    new ExtractedRelation(
                        "Alice", "Bob", "works_with", "first", 1.0d,
                        Map.of("since", "2020-01-01")),
                    List.of("chunk-1")),
                new RefinedRelationPatch(
                    new ExtractedRelation(
                        "Bob", "Alice", "works_with", "second", 1.0d,
                        Map.of("confidence", 0.9d)),
                    List.of("chunk-1"))
            ),
            List.of(),
            true
        );

        var patches = resolver.distribute(refined, window);

        assertThat(patches).hasSize(1);
        assertThat(patches.get(0).relations()).containsExactly(
            new ExtractedRelation(
                "Alice",
                "Bob",
                "works_with",
                "first",
                1.0d,
                Map.of("since", "2020-01-01", "confidence", 0.9d)));
    }

    private static Chunk chunk(String chunkId, String text) {
        return new Chunk(chunkId, "doc-1", text, text.length(), 0, Map.of());
    }
}
