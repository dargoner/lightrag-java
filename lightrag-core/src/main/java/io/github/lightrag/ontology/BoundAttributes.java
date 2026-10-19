package io.github.lightrag.ontology;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Result of binding raw attributes against a typed attribute schema.
 *
 * @param values      the materialized attributes keyed by
 *                    {@link Identity#identityId(String)}; sorted by key and
 *                    unmodifiable
 * @param diagnostics the attribute diagnostics that must be forwarded by the
 *                    caller; unmodifiable and sorted by owner, raw label, and
 *                    reason
 */
public record BoundAttributes(Map<String, Object> values, List<UnmatchedLabel> diagnostics) {
    public BoundAttributes {
        values = values == null || values.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(values));
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
