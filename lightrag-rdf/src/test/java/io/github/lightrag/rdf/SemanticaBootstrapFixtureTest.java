package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyRelationType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Imports the TTL that Semantica's {@code bootstrap_schema} actually emits, so the importer is
 * pinned against the real induction output rather than only against hand-written Turtle.
 *
 * <p>Fixture provenance: Semantica 0.7.0 {@code bootstrap_schema} run with
 * {@code min_occurrences=1}, {@code base_uri="https://example.com/semantica/"} and a sample of
 * four Person/Organization entities plus {@code works_at}/{@code knows} relationships, serialized
 * through rdflib 7.6.0. The output is the unmodified Turtle for that run.
 */
class SemanticaBootstrapFixtureTest {

    @Test
    void importsSemanticaBootstrapTurtle() throws IOException {
        ImportResult result = OntologyRdfImporter.importOntology(readFixture());

        Ontology ontology = result.ontology();
        assertThat(ontology.version()).isEqualTo("1.0");
        assertThat(ontology.entityTypes())
            .extracting(OntologyEntityType::label)
            .containsExactly("Organization", "Person");
        assertThat(ontology.entityTypes())
            .extracting(OntologyEntityType::description)
            .containsExactly(
                "Class representing organization entities",
                "Class representing person entities"
            );
        assertThat(ontology.relationTypes())
            .extracting(OntologyRelationType::label)
            .containsExactly("knows", "worksAt");
        assertThat(ontology.relationTypes())
            .extracting(OntologyRelationType::patterns)
            .containsExactly(
                List.of(new OntologyPattern("Person", "Person")),
                List.of(new OntologyPattern("Person", "Organization"))
            );
        assertThat(result.warnings()).isEmpty();
    }

    private static String readFixture() throws IOException {
        try (InputStream stream = SemanticaBootstrapFixtureTest.class.getResourceAsStream(
            "/semantica/bootstrap_schema.ttl"
        )) {
            assertThat(stream).as("fixture resource").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
