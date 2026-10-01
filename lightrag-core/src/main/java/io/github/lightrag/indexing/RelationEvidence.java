package io.github.lightrag.indexing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The upstream relation-weight rule ({@code operate.py:2980-3000}, order verbatim):
 * <ol>
 *   <li>added weight = count of the RETAINED incoming sources not already reflected in the stored
 *       scalar. Every contribution is 1.0 on the standard extraction path, so the sum over surviving
 *       rows reduces to the count of not-yet-stored source ids. The retained list is the batch after
 *       the source-id cap filter, never the raw incoming list;</li>
 *   <li>weight = stored weight + added;</li>
 *   <li>floor = distinct real sources of the CAPPED merged list, where {@code manual_creation} and
 *       {@code UNKNOWN} are placeholders, not evidence ({@code constants.py:54});</li>
 *   <li>result = max(weight, floor): a legacy or manual boost survives, an undersized scalar is
 *       repaired.</li>
 * </ol>
 */
public final class RelationEvidence {
    private static final Set<String> NO_EVIDENCE_SOURCE_IDS = Set.of("manual_creation", "UNKNOWN");

    private RelationEvidence() {
    }

    public static double merge(
        double storedWeight,
        List<String> retainedIncomingChunkIds,
        List<String> storedChunkIds,
        List<String> cappedMergedChunkIds
    ) {
        var storedSources = new LinkedHashSet<>(storedChunkIds);
        var added = retainedIncomingChunkIds.stream()
            .filter(chunkId -> !chunkId.isEmpty() && !storedSources.contains(chunkId))
            .count();
        return Math.max(storedWeight + added, (double) distinctEvidence(cappedMergedChunkIds));
    }

    public static long distinctEvidence(List<String> chunkIds) {
        var distinct = new LinkedHashSet<String>();
        for (var chunkId : chunkIds) {
            if (carriesEvidence(chunkId)) {
                distinct.add(chunkId);
            }
        }
        return distinct.size();
    }

    /** A real source id: non-blank and not one of upstream's no-evidence placeholders ({@code constants.py:54}). */
    static boolean carriesEvidence(String sourceId) {
        return sourceId != null && !sourceId.isEmpty() && !NO_EVIDENCE_SOURCE_IDS.contains(sourceId);
    }

    /** Upstream {@code validate_relation_weight} ({@code utils_graph.py:192-217}). */
    public static double validateManualWeight(double weight, List<String> storedChunkIds) {
        var evidenceCount = distinctEvidence(storedChunkIds);
        if (weight < evidenceCount) {
            throw new IllegalArgumentException(
                "relation weight " + weight + " cannot be less than its distinct-source evidence count "
                    + evidenceCount
            );
        }
        return weight;
    }
}
