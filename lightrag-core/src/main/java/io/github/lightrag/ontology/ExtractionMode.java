package io.github.lightrag.ontology;

/**
 * Controls how strictly the knowledge extractor follows an ontology overlay.
 *
 * <ul>
 *   <li>{@link #STRICT}: only entity types, relation types, and patterns declared
 *       in the ontology may be extracted. Unknown items are rejected.</li>
 *   <li>{@link #PREFER}: prefer the ontology, but still allow "Other" for unknown
 *       entity types and unknown relation keywords when the text supports them.</li>
 *   <li>{@link #OPEN}: use the ontology as soft hints; extraction is otherwise
 *       unconstrained.</li>
 * </ul>
 */
public enum ExtractionMode {
    STRICT,
    PREFER,
    OPEN
}
