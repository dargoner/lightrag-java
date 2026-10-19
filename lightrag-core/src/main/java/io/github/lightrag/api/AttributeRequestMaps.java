package io.github.lightrag.api;

import io.github.lightrag.ontology.AttributeJsonCodec;
import io.github.lightrag.ontology.AttributeValues;
import io.github.lightrag.ontology.Identity;
import io.github.lightrag.ontology.RawAttribute;
import io.github.lightrag.ontology.RawAttributes;
import io.github.lightrag.ontology.RawIssue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Structural sanitation and identity canonicalization for host-supplied
 * attribute maps on create/edit/update/merge requests.
 *
 * <p>Unlike extraction attributes, manual writes are not projected onto the
 * ontology. They are still subject to the structural limits, are keyed by
 * {@link Identity#identityId(String)}, and must fit the per-record caps.
 * Conflicting values for the same identity are rejected instead of silently
 * dropped. A {@code null} map is handled by the caller because edit/update/merge
 * requests distinguish "keep current" from "clear".
 */
final class AttributeRequestMaps {

    private AttributeRequestMaps() {}

    /** Normalizes a required attribute map; {@code null} becomes empty. */
    static Map<String, Object> required(Map<String, Object> attributes) {
        return canonicalize(attributes == null ? Map.of() : attributes);
    }

    /** Normalizes an optional attribute map; {@code null} stays {@code null}. */
    static Map<String, Object> optional(Map<String, Object> attributes) {
        return attributes == null ? null : canonicalize(attributes);
    }

    private static Map<String, Object> canonicalize(Map<String, Object> attributes) {
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("attribute key must not be null");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                    "attribute value must not be null: " + entry.getKey()
                );
            }
        }
        RawAttributes sanitized = AttributeValues.sanitizeRaw(attributes);
        rejectStructuralIssues(sanitized.issues());
        if (sanitized.candidates().isEmpty()) {
            return Map.of();
        }

        Map<String, List<RawAttribute>> groups = new TreeMap<>();
        for (RawAttribute candidate : sanitized.candidates()) {
            String identity = Identity.identityId(candidate.rawKey());
            groups.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(candidate);
        }

        Map<String, Object> values = new TreeMap<>();
        for (Map.Entry<String, List<RawAttribute>> entry : groups.entrySet()) {
            List<RawAttribute> group = new ArrayList<>(entry.getValue());
            group.sort((left, right) -> left.rawKey().compareTo(right.rawKey()));
            String canonical = canonicalValueJson(group.get(0).value());
            for (int index = 1; index < group.size(); index++) {
                if (!canonical.equals(canonicalValueJson(group.get(index).value()))) {
                    throw new IllegalArgumentException(
                        "conflicting attribute values for key: " + entry.getKey()
                    );
                }
            }
            values.put(entry.getKey(), group.get(0).value());
        }

        Map<String, Object> capped = AttributeValues.applyCaps(values, Set.of());
        if (capped.size() != values.size()) {
            throw new IllegalArgumentException(
                "attribute bag exceeds the per-record limits: " + values.size() + " keys supplied"
            );
        }
        return capped;
    }

    private static void rejectStructuralIssues(List<RawIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        RawIssue issue = issues.get(0);
        throw new IllegalArgumentException(
            "invalid attribute '" + issue.rawKey() + "': " + issue.reason()
        );
    }

    private static String canonicalValueJson(Object value) {
        return AttributeJsonCodec.toJson(Collections.singletonMap("value", value));
    }
}
