package io.github.lightrag.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.types.Relation;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SourceIdLimitsTest {
    @Test
    void keepMethodKeepsTheHeadAndFifoKeepsTheTail() {
        var ids = IntStream.rangeClosed(1, 250).mapToObj(i -> "c" + i).toList();
        assertThat(SourceIdLimits.apply(ids, 200, SourceIdLimits.Method.KEEP)).containsExactlyElementsOf(ids.subList(0, 200));
        assertThat(SourceIdLimits.apply(ids, 200, SourceIdLimits.Method.FIFO)).containsExactlyElementsOf(ids.subList(50, 250));
        assertThat(SourceIdLimits.apply(ids, 0, SourceIdLimits.Method.KEEP)).isEmpty();
        assertThat(SourceIdLimits.apply(ids.subList(0, 3), 200, SourceIdLimits.Method.KEEP)).hasSize(3);
    }

    @Test
    void keepDropsCapEvictedNewSourcesFromTheWeightBase() {
        // 250 brand-new sources, KEEP cap 200: upstream drops the 50 new rows outside the capped list
        // BEFORE the weight sum (operate.py:2916-2929 -> :2980-2989), so the edge weighs 200, not 250.
        var incoming = IntStream.rangeClosed(1, 250).mapToObj(i -> "c" + i).toList();
        var keepCapped = SourceIdLimits.apply(incoming, 200, SourceIdLimits.Method.KEEP); // c1..c200
        var keepRetained = SourceIdLimits.retainIncomingEvidence(incoming, List.of(), keepCapped, SourceIdLimits.Method.KEEP);
        assertThat(keepRetained).containsExactlyElementsOf(keepCapped);
        assertThat(RelationEvidence.merge(0d, keepRetained, List.of(), keepCapped)).isEqualTo(200.0d);

        // FIFO keeps every new row (operate.py:2926-2929): the weight base is the full 250 even though
        // only the tail 200 stay stored, and the floor (200) does not pull it back down.
        var fifoCapped = SourceIdLimits.apply(incoming, 200, SourceIdLimits.Method.FIFO); // c51..c250
        var fifoRetained = SourceIdLimits.retainIncomingEvidence(incoming, List.of(), fifoCapped, SourceIdLimits.Method.FIFO);
        assertThat(fifoRetained).containsExactlyElementsOf(incoming);
        assertThat(RelationEvidence.merge(0d, fifoRetained, List.of(), fifoCapped)).isEqualTo(250.0d);
    }

    @Test
    void withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback() {
        // tracking c1..c250, stored scalar c1..c200 (a previous KEEP cap), a later batch re-feeds c201.
        // Upstream WITH a relation_chunks row keeps that row (c201 is tracked) and weighs 200 + 1 = 201
        // (operate.py:2922-2925, :2980-2989, floor :2993-3000). Java has no relation_chunks analog at the
        // merge point, so it runs upstream's own no-tracking fallback (:2881-2886): the row is dropped and
        // the edge stays at 200. This test pins the documented divergence (see the SourceIdLimits note);
        // a future tracking-store port must flip it deliberately.
        var incoming = List.of("c201");
        var stored = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
        var keepRetained = SourceIdLimits.retainIncomingEvidence(
            incoming, stored, stored, SourceIdLimits.Method.KEEP);
        assertThat(keepRetained).isEmpty();
        assertThat(RelationEvidence.merge(200.0d, keepRetained, stored, stored)).isEqualTo(200.0d);
    }

    @Test
    void theTrackingBaselineShapesForkTheKeepFilterFromTheScalarFallback() {
        // Fork table at limit 2 over the three baselines upstream can hand the KEEP filter - the scalar
        // (Java's no-tracking fallback, :2881-2886), the present-but-empty authoritative row (:2860-2879,
        // NOT reseeded from the scalar), and a tracked list lagging/leading the scalar. Each row
        // recomputes full -> capped -> filter exactly as the merge does (:2889, :2904, :2916-2929); the
        // leading row is the 200/250-scale case of withoutATrackingListTheFilterFollowsUpstreamsNoTrackingFallback.
        var incoming = List.of("c3", "c4");
        var capped = SourceIdLimits.apply(List.of("c1", "c2", "c3", "c4"), 2, SourceIdLimits.Method.KEEP);
        // scalar baseline c1..c3 - Java today, and upstream with no tracking row: c3 is still stored
        assertThat(SourceIdLimits.retainIncomingEvidence(
            incoming, List.of("c1", "c2", "c3"), capped, SourceIdLimits.Method.KEEP))
            .containsExactly("c3");
        // upstream with a present-but-empty row: baseline [], so the cap sees only the new ids
        assertThat(SourceIdLimits.retainIncomingEvidence(incoming, List.of(),
            SourceIdLimits.apply(incoming, 2, SourceIdLimits.Method.KEEP), SourceIdLimits.Method.KEEP))
            .containsExactly("c3", "c4");
        // upstream with a lagging tracked list c1..c2 against the scalar c1..c3: drops the refeed Java keeps
        assertThat(SourceIdLimits.retainIncomingEvidence(
            incoming, List.of("c1", "c2"), capped, SourceIdLimits.Method.KEEP))
            .isEmpty();
        // upstream with a leading tracked list c1..c3 against the scalar c1..c2: keeps the cap-evicted
        // refeed Java drops (the divergence scenario of the 200/250 test above)
        assertThat(SourceIdLimits.retainIncomingEvidence(
            incoming, List.of("c1", "c2", "c3"), capped, SourceIdLimits.Method.KEEP))
            .containsExactly("c3");
    }

    @Test
    void repeatedRefeedsOfACappedTrackedSourceAccumulateUpstreamOnly() {
        // Tracking ahead: tracking c1..c250, stored scalar c1..c200, and the SAME cap-evicted source c201
        // is re-fed three times. Upstream keeps the tracked row every event (:2922-2925) and the weight
        // filter reads the stored scalar (:2980-2986) - c201 never enters it, because the join persists
        // the CAPPED list (:2904, :2954) - so the weight grows 200 -> 201 -> 202 -> 203; the gap is one
        // surviving row's weight per re-feed EVENT (1.0 here; upstream sums each surviving row's
        // dp["weight"]), not per source. Java drops the row every time and stays at 200.
        var incoming = List.of("c201");
        var stored = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
        var retained = SourceIdLimits.retainIncomingEvidence(incoming, stored, stored, SourceIdLimits.Method.KEEP);
        assertThat(retained).isEmpty(); // Java's no-tracking filter
        var javaWeight = 200.0d;
        for (int refeed = 0; refeed < 3; refeed++) { // each event merges the previous record
            javaWeight = RelationEvidence.merge(javaWeight, retained, stored, stored);
        }
        assertThat(javaWeight).isEqualTo(200.0d); // upstream-with-tracking reaches 203
    }

    @Test
    void cappedMergeKeepsTheStoredRecordWhenTheRefeedFallsOutsideTheCap() {
        // The COMPLETE merge (IndexingPipeline.mergeRelationWithCaps, extracted package-private so it is
        // directly testable), not just the two helpers: existing c1..c200 / weight 200, a later batch
        // re-feeds c201 under the 200 KEEP cap. Java's no-tracking merge drops the fragment, so the merged
        // RECORD equals the stored one - the same record upstream's no-tracking early return hands back
        // (:2932-2951). Upstream WITH a tracking row would instead return a record whose
        // description/keywords carry c201's fragment and weigh 201 (see the fork table above).
        var storedIds = IntStream.rangeClosed(1, 200).mapToObj(i -> "c" + i).toList();
        var existing = new GraphStore.RelationRecord(
            "e1~e2", "e1", "e2", "kw", "stored description", 200.0d, storedIds);
        var incoming = new Relation("e1~e2", "e1", "e2", "kw", "c201 description", 1.0d, List.of("c201"));

        var merged = IndexingPipeline.mergeRelationWithCaps(
            existing,
            incoming,
            true,
            200,
            SourceIdLimits.Method.KEEP,
            FilePathLimits.DEFAULT_MAX_FILE_PATHS,
            DescriptionSummarizer.withoutLlm()
        );

        assertThat(merged).isEqualTo(existing); // ids, weight, description, keywords all unchanged
        assertThat(merged.sourceChunkIds()).hasSize(200);
        assertThat(merged.weight()).isEqualTo(200.0d);
    }
}
