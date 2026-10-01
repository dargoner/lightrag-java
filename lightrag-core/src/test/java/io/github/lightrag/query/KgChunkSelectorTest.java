package io.github.lightrag.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KgChunkSelectorTest {

    private static KgChunkSelector.Group group(String groupId, String... chunkIds) {
        return new KgChunkSelector.Group(groupId, List.of(chunkIds), 1.0d);
    }

    @Test
    void dedupeKeepsChunksForTheEarlierPositionedGroupAndDropsEmptiedGroups() {
        var groups = List.of(
            group("e1", "a", "b"),
            group("e2", "b", "c"),
            group("e3", "c")
        );
        var tracked = KgChunkSelector.dedupeByFirstOwner(groups);
        assertThat(tracked.groups()).extracting(KgChunkSelector.Group::groupId).containsExactly("e1", "e2");
        // b occurs in two groups, so occurrence-desc ordering places it first (upstream operate.py:6436-6442).
        assertThat(tracked.groups().get(0).chunkIds()).containsExactly("b", "a");
        assertThat(tracked.groups().get(1).chunkIds()).containsExactly("c");
        assertThat(tracked.frequency()).containsEntry("b", 2).containsEntry("c", 2);
    }

    @Test
    void ordersEachGroupsChunksByOccurrenceCountDescending() {
        var tracked = KgChunkSelector.dedupeByFirstOwner(List.of(
            group("e1", "a", "b"), group("e2", "b")
        ));
        assertThat(tracked.groups().get(0).chunkIds()).containsExactly("b", "a");
    }

    @Test
    void weightedPollingAllocatesALinearGradientAcrossGroups() {
        var groups = List.of(group("e1", "a1", "a2", "a3"), group("e2", "b1", "b2"), group("e3", "c1"));
        assertThat(KgChunkSelector.pickByWeightedPolling(groups, 3, 1))
            .containsExactly("a1", "a2", "a3", "b1", "b2", "c1");
    }

    @Test
    void weightedPollingSpreadsLeftoverQuotaInAdditionalRounds() {
        var groups = List.of(group("e1", "a1"), group("e2", "b1", "b2", "b3", "b4"));
        assertThat(KgChunkSelector.pickByWeightedPolling(groups, 4, 1))
            .containsExactly("a1", "b1", "b2", "b3", "b4");
    }

    @Test
    void weightedPollingReturnsSingleGroupPrefixAndHonoursKillSwitch() {
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(group("e1", "a", "b", "c")), 2, 1))
            .containsExactly("a", "b");
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(group("e1", "a")), 0, 1)).isEmpty();
        assertThat(KgChunkSelector.pickByWeightedPolling(List.of(), 5, 1)).isEmpty();
    }

    @Test
    void vectorQuotaMatchesUpstreamFlooredFormula() {
        assertThat(KgChunkSelector.vectorQuota(5, 4)).isEqualTo(10);   // 5 * 4 / 2
        assertThat(KgChunkSelector.vectorQuota(5, 1)).isEqualTo(2);    // 5 * 1 / 2
        assertThat(KgChunkSelector.vectorQuota(1, 1)).isEqualTo(1);    // floor of 1
        assertThat(KgChunkSelector.vectorQuota(0, 5)).isZero();        // kill switch
        assertThat(KgChunkSelector.vectorQuota(5, 0)).isZero();
    }
}
