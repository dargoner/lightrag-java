package io.github.lightrag.ontology.rdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdfIriEncoderTest {

    private static final String BASE = "https://example.com/onto/";
    private static final String SHAPES = "https://example.com/onto/shapes/";

    private final RdfIriEncoder encoder = new RdfIriEncoder(BASE, SHAPES);

    @Test
    void percentEncodesUtf8BytesAndLeavesOnlyUnreservedCharacters() {
        assertThat(RdfIriEncoder.percentEncode("person name")).isEqualTo("person%20name");
        assertThat(RdfIriEncoder.percentEncode("person/name")).isEqualTo("person%2Fname");
        assertThat(RdfIriEncoder.percentEncode("100%")).isEqualTo("100%25");
        assertThat(RdfIriEncoder.percentEncode("中文")).isEqualTo("%E4%B8%AD%E6%96%87");
        assertThat(RdfIriEncoder.percentEncode("a.b")).isEqualTo("a%2Eb");
        assertThat(RdfIriEncoder.percentEncode("..")).isEqualTo("%2E%2E");
        assertThat(RdfIriEncoder.percentEncode("A-z_~09")).isEqualTo("A-z_~09");
        assertThat(RdfIriEncoder.percentEncode("?&=#")).isEqualTo("%3F%26%3D%23");
    }

    @Test
    void percentEncodingIsInjective() {
        List<String> inputs = List.of(
            "person name",
            "person/name",
            "person%20name",
            "person%2Fname",
            "person.name",
            "person%2Ename",
            "person",
            "Person",
            "PERSON",
            "%7E",
            "~",
            "中文"
        );

        List<String> encoded = inputs.stream().map(RdfIriEncoder::percentEncode).toList();

        assertThat(encoded).doesNotHaveDuplicates();
        assertThat(encoded).hasSameSizeAs(inputs);
    }

    @Test
    void layeredFactoryMethodsUseIdentityAndIsolateNamespaces() {
        assertThat(encoder.entityClass("Person Name"))
            .isEqualTo(BASE + "class/person%20name");
        assertThat(encoder.entityClass("PERSON NAME"))
            .isEqualTo(encoder.entityClass("  person   name  "));
        assertThat(encoder.relationProperty("Works At"))
            .isEqualTo(BASE + "relation/works%20at");
        assertThat(encoder.entityAttribute("Person", "Full Name"))
            .isEqualTo(BASE + "attribute/entity/person/full%20name");
        assertThat(encoder.relationAttribute("Works At", "Since"))
            .isEqualTo(BASE + "attribute/relation/works%20at/since");
        assertThat(encoder.shaclNode("Person"))
            .isEqualTo(SHAPES + "class/person/shape");
        assertThat(encoder.shaclPropertyNode("Person", "Full Name"))
            .isEqualTo(SHAPES + "class/person/property/full%20name");
        assertThat(encoder.relationPatternNode("Works At", "Person", "Organization"))
            .isEqualTo(BASE + "relationPattern/works%20at/person/organization");
        assertThat(encoder.sourceBindingNode(encoder.entityClass("Person")))
            .isEqualTo(BASE + "class/person/sourceBinding");
    }

    @Test
    void entityAndRelationNamespacesDoNotCollide() {
        assertThat(encoder.entityClass("works at"))
            .isNotEqualTo(encoder.relationProperty("works at"));
        assertThat(encoder.entityAttribute("person", "name"))
            .isNotEqualTo(encoder.relationAttribute("person", "name"));
    }

    @Test
    void dotSegmentsAreEncodedSoTheyCannotBeNormalizedAway() {
        assertThat(encoder.entityClass(".")).isEqualTo(BASE + "class/%2E");
        assertThat(encoder.entityClass("..")).isEqualTo(BASE + "class/%2E%2E");
        assertThat(encoder.entityAttribute(".", ".."))
            .isEqualTo(BASE + "attribute/entity/%2E/%2E%2E");
    }

    @Test
    void rejectsMissingTrailingSlashOrRelativeBases() {
        assertThatThrownBy(() -> new RdfIriEncoder("https://example.com/onto"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end with '/'");
        assertThatThrownBy(() -> new RdfIriEncoder("not-an-absolute-iri/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute IRI");
        assertThatThrownBy(() -> new RdfIriEncoder("/relative/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute IRI");
        assertThatThrownBy(() -> new RdfIriEncoder(BASE, "https://example.com/shapes"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shapesBase");
    }

    @Test
    void rejectsForbiddenCharactersInBaseIris() {
        assertThatThrownBy(() -> new RdfIriEncoder("https://example.com/on to/"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RdfIriEncoder("https://example.com/<onto>/"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RdfIriEncoder("https://example.com/onto\\/"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptySegmentsAndBlankIdentities() {
        assertThatThrownBy(() -> RdfIriEncoder.percentEncode(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RdfIriEncoder.percentEncode(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.entityClass("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encoder.entityAttribute("Person", " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnpairedSurrogates() {
        assertThatThrownBy(() -> RdfIriEncoder.percentEncode("\uD800"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("surrogate");
    }

    @Test
    void rejectsGeneratedIrisOverTheByteLimit() {
        String longLabel = "a".repeat(RdfIriEncoder.MAX_IRI_BYTES);

        assertThatThrownBy(() -> encoder.entityClass(longLabel))
            .isInstanceOf(OntologyRdfExportException.class)
            .hasMessageContaining("8192");
    }
}
