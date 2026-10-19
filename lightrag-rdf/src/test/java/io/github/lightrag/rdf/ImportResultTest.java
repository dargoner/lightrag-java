package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Ontology;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportResultTest {

    private static Ontology emptyOntology() {
        return new Ontology(List.of(), List.of(), null, Map.of());
    }

    @Test
    void warningsAreSortedAndDeduplicated() {
        ImportResult result = new ImportResult(
            emptyOntology(),
            List.of("b", "a", "b"),
            null
        );

        assertThat(result.warnings()).containsExactly("a", "b");
        assertThat(result.stats()).isEqualTo(ImportResult.ImportStats.empty());
    }

    @Test
    void nullWarningsBecomeAnEmptyList() {
        ImportResult result = new ImportResult(emptyOntology(), null, null);

        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void ontologyIsRequired() {
        assertThatThrownBy(() -> new ImportResult(null, List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
