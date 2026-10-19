package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Ontology;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Result of a successful RDF import.
 *
 * <p>The ontology is a draft value: the importer never assigns a version,
 * writes a store, or applies governance changes. Warnings are deduplicated and
 * sorted in natural string order so identical inputs produce identical
 * results.
 *
 * @param ontology the imported ontology (never {@code null})
 * @param warnings deterministic, sorted warnings
 * @param stats    statement and declaration counts
 */
public record ImportResult(
    Ontology ontology,
    List<String> warnings,
    ImportStats stats
) {

    public ImportResult {
        Objects.requireNonNull(ontology, "ontology");
        warnings = warnings == null
            ? List.of()
            : List.copyOf(new TreeSet<>(warnings));
        stats = stats == null ? ImportStats.empty() : stats;
    }

    /**
     * Counts collected during import.
     *
     * @param statements            accepted RDF statements
     * @param entityTypes           imported entity types
     * @param relationTypes         imported relation types
     * @param properties            imported entity and relation properties
     * @param patterns              imported relation patterns
     * @param sourceBindings        attached source bindings
     * @param ignoredAxiomStatements statements ignored as unsupported axioms
     */
    public record ImportStats(
        int statements,
        int entityTypes,
        int relationTypes,
        int properties,
        int patterns,
        int sourceBindings,
        int ignoredAxiomStatements
    ) {

        /** Returns an all-zero instance. */
        public static ImportStats empty() {
            return new ImportStats(0, 0, 0, 0, 0, 0, 0);
        }
    }
}
