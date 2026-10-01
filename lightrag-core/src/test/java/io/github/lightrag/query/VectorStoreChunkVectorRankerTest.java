package io.github.lightrag.query;

import io.github.lightrag.storage.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VectorStoreChunkVectorRankerTest {

    @Test
    void returnsOnlyRankedMatchesSoEmptyStoresTriggerTheWeightFallback() {
        var ranker = new VectorStoreChunkVectorRanker(storeReturning(List.of()));

        assertThat(ranker.rank(List.of(1.0d), List.of("a", "b"), 5)).isEmpty();
    }

    @Test
    void partialCoverageKeepsMatchedIdsWithoutAppendingUnrankedCandidates() {
        var ranker = new VectorStoreChunkVectorRanker(storeReturning(List.of(
            new VectorStore.VectorMatch("b", 0.9d),
            new VectorStore.VectorMatch("not-a-candidate", 0.95d)
        )));

        assertThat(ranker.rank(List.of(1.0d), List.of("a", "b", "c"), 5)).containsExactly("b");
    }

    @Test
    void returnsNothingForEmptyCandidatesOrNonPositiveTopK() {
        var ranker = new VectorStoreChunkVectorRanker(storeReturning(List.of(
            new VectorStore.VectorMatch("a", 1.0d)
        )));

        assertThat(ranker.rank(List.of(1.0d), List.of(), 5)).isEmpty();
        assertThat(ranker.rank(List.of(1.0d), List.of("a"), 0)).isEmpty();
    }

    private static VectorStore storeReturning(List<VectorStore.VectorMatch> matches) {
        return new VectorStore() {
            @Override
            public void saveAll(String namespace, List<VectorRecord> vectors) {
            }

            @Override
            public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
                return matches;
            }

            @Override
            public List<VectorRecord> list(String namespace) {
                return List.of();
            }
        };
    }
}
