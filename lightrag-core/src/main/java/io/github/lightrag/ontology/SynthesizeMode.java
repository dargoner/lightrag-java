package io.github.lightrag.ontology;

/**
 * Strategy used to synthesize an ontology candidate from a base ontology and a
 * draft.
 */
public enum SynthesizeMode {
    /**
     * The draft is an incremental declaration that is merged into the base.
     * Items not listed in the draft are retained.
     */
    REGISTER_INCREMENTAL,

    /**
     * The draft is a complete replacement of the base ontology.
     */
    EVOLUTION_REPLACE
}
