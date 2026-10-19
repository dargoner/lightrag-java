package io.github.lightrag.ontology;

import java.util.List;

/**
 * Result of the structural sanitation pass: every raw key/value pair is kept as
 * a candidate, and structural problems are reported separately.
 *
 * @param candidates the structurally valid candidates, in input order
 * @param issues     the structural problems; may contain one issue per rejected
 *                   key, or a single whole-object issue
 */
public record RawAttributes(List<RawAttribute> candidates, List<RawIssue> issues) {
    public RawAttributes {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
