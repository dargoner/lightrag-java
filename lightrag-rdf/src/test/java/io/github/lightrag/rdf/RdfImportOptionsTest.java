package io.github.lightrag.rdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdfImportOptionsTest {

    @Test
    void defaultsMatchTheDocumentedLimits() {
        RdfImportOptions options = RdfImportOptions.defaults();

        assertThat(options.format()).isEqualTo(RdfImportFormat.AUTO);
        assertThat(options.baseIri()).isNull();
        assertThat(options.includeSourceBindings()).isTrue();
        assertThat(options.allowUnknownAxioms()).isTrue();
        assertThat(options.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(options.maxTriples()).isEqualTo(200_000);
        assertThat(options.maxDepth()).isEqualTo(32);
        assertThat(options.maxNodes()).isEqualTo(10_000);
        assertThat(options.maxLiteralBytes()).isEqualTo(64 * 1024);
        assertThat(options.maxIriBytes()).isEqualTo(8 * 1024);
        assertThat(options.extensionNamespace()).isNull();
    }

    @Test
    void builderRoundTripsThroughToBuilder() {
        RdfImportOptions original = RdfImportOptions.builder()
            .format(RdfImportFormat.TURTLE)
            .baseIri("https://example.com/base/")
            .includeSourceBindings(false)
            .allowUnknownAxioms(false)
            .maxBytes(1024)
            .maxTriples(10)
            .maxDepth(4)
            .maxNodes(20)
            .maxLiteralBytes(128)
            .maxIriBytes(256)
            .extensionNamespace("https://example.com/lg#")
            .build();

        assertThat(original.toBuilder().build()).isEqualTo(original);
    }

    @Test
    void baseIriMustBeAnAbsoluteIri() {
        assertThat(RdfImportOptions.builder()
            .baseIri("https://example.com/onto")
            .build()
            .baseIri())
            .isEqualTo("https://example.com/onto");

        assertThatThrownBy(() -> RdfImportOptions.builder()
            .baseIri("not an iri")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be an absolute IRI");

        assertThatThrownBy(() -> RdfImportOptions.builder()
            .baseIri("https://example.com/a b")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("forbidden");
    }

    @Test
    void extensionNamespaceMustEndWithSlashOrHash() {
        assertThatThrownBy(() -> RdfImportOptions.builder()
            .extensionNamespace("https://example.com/lg")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must end with '/' or '#'");
    }

    @Test
    void allLimitsMustBePositive() {
        assertThatThrownBy(() -> RdfImportOptions.builder().maxBytes(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxBytes must be positive");
        assertThatThrownBy(() -> RdfImportOptions.builder().maxTriples(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxTriples must be positive");
        assertThatThrownBy(() -> RdfImportOptions.builder().maxDepth(-1).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxDepth must be positive");
        assertThatThrownBy(() -> RdfImportOptions.builder().maxNodes(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxNodes must be positive");
        assertThatThrownBy(() -> RdfImportOptions.builder().maxLiteralBytes(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxLiteralBytes must be positive");
        assertThatThrownBy(() -> RdfImportOptions.builder().maxIriBytes(0).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxIriBytes must be positive");
    }

    @Test
    void nullFormatDefaultsToAuto() {
        assertThat(RdfImportOptions.builder().format(null).build().format())
            .isEqualTo(RdfImportFormat.AUTO);
    }
}
