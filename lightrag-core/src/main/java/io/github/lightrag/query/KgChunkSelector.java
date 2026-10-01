package io.github.lightrag.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure helpers behind the KG-to-chunk selection stage, ported from the upstream
 * {@code _find_related_text_unit_from_entities} pre-selection steps
 * ({@code operate.py:6399-6443}) and {@code pick_by_weighted_polling}
 * ({@code utils.py:6642-6721}).
 */
final class KgChunkSelector {

    record Group(String groupId, List<String> chunkIds, double score) {
        Group {
            groupId = Objects.requireNonNull(groupId, "groupId");
            chunkIds = List.copyOf(Objects.requireNonNull(chunkIds, "chunkIds"));
        }
    }

    record Tracked(List<Group> groups, Map<String, Integer> frequency) {
    }

    private KgChunkSelector() {
    }

    /**
     * Keeps each chunk for the earliest-positioned group that carries it, drops groups
     * emptied by that rule, then re-orders every surviving group's chunks by global
     * occurrence count, descending (upstream {@code operate.py:6399-6443}).
     */
    static Tracked dedupeByFirstOwner(List<Group> groups) {
        var occurrence = new LinkedHashMap<String, Integer>();
        var deduped = new ArrayList<Group>(groups.size());
        for (var group : groups) {
            var kept = new ArrayList<String>();
            for (var chunkId : group.chunkIds()) {
                if (occurrence.merge(chunkId, 1, Integer::sum) == 1) {
                    kept.add(chunkId);
                }
            }
            if (!kept.isEmpty()) {
                deduped.add(new Group(group.groupId(), kept, group.score()));
            }
        }
        var ordered = deduped.stream()
            .map(group -> new Group(group.groupId(), group.chunkIds().stream()
                .sorted(Comparator.comparingInt((String id) -> occurrence.getOrDefault(id, 0))
                    .reversed())
                .toList(), group.score()))
            .toList();
        return new Tracked(ordered, Map.copyOf(occurrence));
    }

    /** Upstream {@code _vector_chunk_quota} ({@code operate.py:6316-6347}); {@code max <= 0} is a kill switch. */
    static int vectorQuota(int maxRelatedChunks, int groupCount) {
        if (maxRelatedChunks <= 0 || groupCount <= 0) {
            return 0;
        }
        return Math.max(1, maxRelatedChunks * groupCount / 2);
    }

    /** Upstream {@code pick_by_weighted_polling} ({@code utils.py:6642-6721}). */
    static List<String> pickByWeightedPolling(List<Group> groups, int maxRelatedChunks, int minRelatedChunks) {
        if (groups.isEmpty() || maxRelatedChunks <= 0) {
            return List.of();
        }
        if (groups.size() == 1) {
            return groups.get(0).chunkIds().stream().limit(maxRelatedChunks).toList();
        }
        var expected = new int[groups.size()];
        for (var index = 0; index < groups.size(); index++) {
            var ratio = (double) index / (groups.size() - 1);
            expected[index] = (int) Math.round(maxRelatedChunks - ratio * (maxRelatedChunks - minRelatedChunks));
        }
        var selected = new ArrayList<String>();
        var used = new int[groups.size()];
        var remaining = 0;
        for (var index = 0; index < groups.size(); index++) {
            var available = groups.get(index).chunkIds();
            var actual = Math.min(expected[index], available.size());
            selected.addAll(available.subList(0, actual));
            used[index] = actual;
            remaining += Math.max(0, expected[index] - actual);
        }
        for (var round = 0; round < remaining; round++) {
            var allocated = false;
            for (var index = 0; index < groups.size(); index++) {
                var available = groups.get(index).chunkIds();
                if (used[index] < available.size()) {
                    selected.add(available.get(used[index]));
                    used[index]++;
                    allocated = true;
                    break;
                }
            }
            if (!allocated) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
