package io.github.lightrag.indexing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Source-id cap behaviour, a direct port of upstream {@code apply_source_ids_limit}
 * ({@code utils.py:7244-7276}, marked 2025-10) plus the KEEP-mode weight-base row filter of the
 * relation merge ({@code operate.py:2916-2929}).
 *
 * <p>Upstream caps the {@code source_id} of every entity and relation at
 * {@code max_source_ids_per_entity} / {@code max_source_ids_per_relation} (default 200,
 * {@code constants.py:71-72}), keeping either the head ({@code KEEP}, also spelled {@code IGNORE_NEW}
 * in configuration) or the tail ({@code FIFO}) of the merged id list.</p>
 */
public final class SourceIdLimits {
    public static final int DEFAULT_MAX_SOURCE_IDS = 200;

    public enum Method {
        KEEP,
        FIFO
    }

    private SourceIdLimits() {
    }

    /** Upstream configuration spellings: {@code FIFO}, {@code KEEP} and its legacy alias {@code IGNORE_NEW}. */
    public static Method parse(String value) {
        var normalized = Objects.requireNonNull(value, "value").strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FIFO" -> Method.FIFO;
            case "KEEP", "IGNORE_NEW" -> Method.KEEP;
            default -> throw new IllegalArgumentException("unknown source ids limit method: " + value);
        };
    }

    /**
     * Upstream {@code apply_source_ids_limit} verbatim: a non-positive limit empties the list, an
     * undersized list passes through unchanged, otherwise KEEP keeps the head and FIFO the tail.
     */
    public static List<String> apply(List<String> sourceIds, int limit, Method method) {
        var ids = List.copyOf(Objects.requireNonNull(sourceIds, "sourceIds"));
        Objects.requireNonNull(method, "method");
        if (limit <= 0) {
            return List.of();
        }
        if (ids.size() <= limit) {
            return ids;
        }
        return method == Method.FIFO
            ? List.copyOf(ids.subList(ids.size() - limit, ids.size()))
            : List.copyOf(ids.subList(0, limit));
    }

    /**
     * The weight-base row filter of the relation merge ({@code operate.py:2916-2929}): under KEEP a
     * fragment whose source the cap threw away is dropped unless its source is still in the existing
     * baseline, so 250 brand-new sources under a 200 cap add 200, never 250; under FIFO every fragment
     * is kept (:2926-2929).
     *
     * <p>{@code existingSourceIds} stands in for upstream's {@code existing_full_source_ids} slot,
     * which filters the historical no-evidence placeholders ({@code :2881-2886}); the capped merged
     * list is used unfiltered, exactly like upstream's {@code set(source_ids)}. Java has no
     * relation-chunks tracking store at the merge point, so it always passes the stored scalar — that
     * is upstream's own no-tracking path, whose divergences are documented on the merge helper.</p>
     */
    public static List<String> retainIncomingEvidence(
        List<String> incoming,
        List<String> existingSourceIds,
        List<String> cappedMerged,
        Method method
    ) {
        var rows = List.copyOf(Objects.requireNonNull(incoming, "incoming"));
        if (Objects.requireNonNull(method, "method") == Method.FIFO) {
            return rows;
        }
        var allowed = new LinkedHashSet<>(Objects.requireNonNull(cappedMerged, "cappedMerged"));
        for (var sourceId : Objects.requireNonNull(existingSourceIds, "existingSourceIds")) {
            if (RelationEvidence.carriesEvidence(sourceId)) {
                allowed.add(sourceId);
            }
        }
        var retained = new ArrayList<String>(rows.size());
        for (var sourceId : rows) {
            if (sourceId == null || sourceId.isEmpty() || allowed.contains(sourceId)) {
                retained.add(sourceId);
            }
        }
        return List.copyOf(retained);
    }
}
