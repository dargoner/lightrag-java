package io.github.lightrag.ontology;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Standalone read-only guard for model-generated Cypher.
 *
 * <p>Unlike {@link CypherOntologyChecker}, this check does not depend on an applied
 * ontology snapshot or physical graph profile, so it stays mandatory when the
 * ontology layer degrades. It rejects write/unsupported keywords
 * (CREATE/MERGE/SET/DELETE/REMOVE/DROP/DETACH/FOREACH/CALL/LOAD/INSERT/APOC) and
 * unicode escapes in code position, ignoring occurrences inside string literals,
 * comments and backtick-quoted identifiers.
 *
 * <p>A failure of this guard is a hard rejection: callers must not execute the
 * statement and must not fall back to an "unknown" state.
 */
public final class CypherReadOnlyGuard {

    private CypherReadOnlyGuard() {}

    /**
     * Result of the read-only check.
     *
     * @param ok         true when no write or unsupported construct was found
     * @param violations human-readable violations; empty when {@code ok}
     */
    public record Result(boolean ok, List<String> violations) {
        public Result {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }
    }

    /**
     * Checks one statement or a multi-statement script for write capability.
     *
     * @param cypher statement text; null and blank are treated as empty text
     * @return the check outcome
     */
    public static Result check(String cypher) {
        String text = cypher == null ? "" : cypher;
        CypherOntologyChecker.Masks masks = CypherOntologyChecker.maskFor(text, "");
        LinkedHashSet<String> violations = new LinkedHashSet<>();
        CypherOntologyChecker.checkReadOnly(masks, violations);
        CypherOntologyChecker.checkUnicodeEscapesOutsideTokens(text, masks, violations);
        return new Result(violations.isEmpty(), List.copyOf(violations));
    }
}
