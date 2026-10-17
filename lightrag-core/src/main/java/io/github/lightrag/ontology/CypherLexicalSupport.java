package io.github.lightrag.ontology;

/**
 * Lexical masking façade for callers that need to inspect Cypher structure without
 * a full parser.
 *
 * <p>{@link #mask(String)} blanks string literals and comments to spaces while
 * preserving length, and keeps backtick-quoted identifiers (flagging their
 * positions), so keyword or alias scans cannot be fooled by text inside strings
 * or comments. It is the same masking used by {@link CypherReadOnlyGuard} and
 * {@link CypherOntologyChecker}.
 */
public final class CypherLexicalSupport {

    private CypherLexicalSupport() {}

    /**
     * Length-preserving masked view of a statement.
     *
     * @param structure  source with string literals and comments replaced by spaces
     * @param inBacktick positions that belong to backtick-quoted identifiers
     */
    public record Masked(String structure, boolean[] inBacktick) {
        public Masked {
            structure = structure == null ? "" : structure;
            inBacktick = inBacktick == null ? new boolean[0] : inBacktick;
        }
    }

    /**
     * Masks one statement or script.
     *
     * @param cypher statement text; null is treated as empty
     * @return the masked view
     */
    public static Masked mask(String cypher) {
        String text = cypher == null ? "" : cypher;
        CypherOntologyChecker.Masks masks = CypherOntologyChecker.maskFor(text, "");
        return new Masked(masks.structure(), masks.inBacktick());
    }
}
