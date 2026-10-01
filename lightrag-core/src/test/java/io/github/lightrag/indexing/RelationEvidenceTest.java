package io.github.lightrag.indexing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationEvidenceTest {
    @Test
    void evidenceFloorRepairsLegacyRowsAndIgnoresManualSources() {
        assertThat(RelationEvidence.merge(0.5d, List.of(), List.of("c1", "c2"), List.of("c1", "c2")))
            .isEqualTo(2.0d);
        assertThat(RelationEvidence.merge(
            0.5d,
            List.of(),
            List.of("manual_creation", "UNKNOWN"),
            List.of("manual_creation", "UNKNOWN")
        )).isEqualTo(0.5d);
        assertThat(RelationEvidence.merge(1.0d, List.of("c1", "c2"), List.of("c1"), List.of("c1", "c2")))
            .isEqualTo(2.0d);
        assertThat(RelationEvidence.merge(9.0d, List.of("c1"), List.of("c1"), List.of("c1")))
            .isEqualTo(9.0d);
    }

    @Test
    void evidenceFloorCountsTheCappedStoredListNotThePreCapUnion() {
        var capped = IntStream.rangeClosed(1, 200).mapToObj(index -> "c" + index).toList();

        assertThat(RelationEvidence.merge(1.0d, List.of(), capped, capped)).isEqualTo(200.0d);
    }

    @Test
    void capDroppedNewSourcesAddNoWeight() {
        var retained = IntStream.rangeClosed(1, 200).mapToObj(index -> "c" + index).toList();
        assertThat(RelationEvidence.merge(0d, retained, List.of(), retained)).isEqualTo(200.0d);

        var allFifoRows = IntStream.rangeClosed(1, 250).mapToObj(index -> "c" + index).toList();
        var fifoCapped = allFifoRows.subList(50, 250);
        assertThat(RelationEvidence.merge(0d, allFifoRows, List.of(), fifoCapped)).isEqualTo(250.0d);
    }

    @Test
    void distinctEvidenceDropsBlanksAndPlaceholders() {
        assertThat(RelationEvidence.distinctEvidence(List.of("c1", "c1", "", "UNKNOWN", "manual_creation", "c2")))
            .isEqualTo(2);
    }

    @Test
    void manualWeightBelowTheEvidenceCountIsRejected() {
        assertThat(RelationEvidence.validateManualWeight(2.0d, List.of("c1", "c2"))).isEqualTo(2.0d);
        assertThat(RelationEvidence.validateManualWeight(0.5d, List.of("UNKNOWN"))).isEqualTo(0.5d);
        assertThatThrownBy(() -> RelationEvidence.validateManualWeight(0.5d, List.of("c1", "c2")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence count 2");
    }
}
