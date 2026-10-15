package io.github.lightrag.ontology;

import io.github.lightrag.api.GraphExtractionOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyTaskContextTest {

    @Test
    void optionsOnlyContextIsNotEmpty() {
        var optionsOnly = new OntologyTaskContext(
            GraphExtractionOptions.builder().enabled(false).build(),
            null,
            null,
            false
        );

        assertThat(OntologyTaskContext.empty().isEmpty()).isTrue();
        assertThat(optionsOnly.isEmpty()).isFalse();
    }

    @Test
    void explicitDefaultValuedContextIsBoundWhileEmptyContextStaysUnbound() {
        var boundDefaults = new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, false);
        var unbound = OntologyTaskContext.empty();

        assertThat(boundDefaults.graphExtractionOptions()).isEqualTo(unbound.graphExtractionOptions());
        assertThat(boundDefaults.ontology()).isEqualTo(unbound.ontology());
        assertThat(boundDefaults.overlay()).isEqualTo(unbound.overlay());
        assertThat(boundDefaults.dropOnUnknownKeyword()).isEqualTo(unbound.dropOnUnknownKeyword());

        assertThat(unbound.isEmpty()).isTrue();
        assertThat(unbound.bound()).isFalse();
        assertThat(boundDefaults.isEmpty()).isFalse();
        assertThat(boundDefaults.bound()).isTrue();
    }

    @Test
    void restoredStoredContextIsBound() {
        var restored = OntologyJsonCodec.readContext(OntologyJsonCodec.writeContext(OntologyTaskContext.empty()));

        assertThat(restored.isEmpty()).isFalse();
        assertThat(restored.bound()).isTrue();
    }

    @Test
    void readContextRejectsSchemaVersionOnlyDocument() {
        assertThatThrownBy(() -> OntologyJsonCodec.readContext("{\"schemaVersion\":1}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must contain a graphExtractionOptions object");
    }

    @Test
    void readContextRejectsMalformedDocumentsExplicitly() {
        assertThatThrownBy(() -> OntologyJsonCodec.readContext("[\"schemaVersion\",1]"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be a JSON object");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext("{\"schemaVersion\":2}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported context schema version: 2");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            "{\"schemaVersion\":1,\"graphExtractionOptions\":\"nope\",\"ontology\":{},\"overlay\":{}}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must contain a graphExtractionOptions object");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            "{\"schemaVersion\":1,\"graphExtractionOptions\":{},\"ontology\":[],\"overlay\":{}}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must contain an ontology object");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            "{\"schemaVersion\":1,\"graphExtractionOptions\":{},\"ontology\":{},\"overlay\":7}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must contain an overlay object");
    }

    @Test
    void readContextRejectsLooseOrIncompleteSnapshots() {
        String valid = OntologyJsonCodec.writeContext(
            new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, true));

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1.5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported context schema version: 1.5");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"dropOnUnknownKeyword\":true", "\"dropOnUnknownKeyword\":{}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boolean dropOnUnknownKeyword");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"dropOnUnknownKeyword\":true,", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boolean dropOnUnknownKeyword");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"mode\":\"PREFER\",", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mode");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"entityTypes\":[],", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entityTypes");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            "{\"schemaVersion\":1,\"dropOnUnknownKeyword\":false,\"graphExtractionOptions\":{},"
                + "\"ontology\":{},\"overlay\":{}}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must contain a valid");

        assertThat(OntologyJsonCodec.readContext(valid).dropOnUnknownKeyword()).isTrue();
    }

    @Test
    void readContextRejectsTruncatedVersionAndCoercedScalars() {
        String valid = OntologyJsonCodec.writeContext(
            new OntologyTaskContext(GraphExtractionOptions.defaults(), null, null, true));

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replace("\"schemaVersion\":1", "\"schemaVersion\":4294967297")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported context schema version: 4294967297");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replaceAll("\"enabled\":(true|false)", "\"enabled\":0")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replaceAll("\"enabled\":(true|false)", "\"enabled\":\"false\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replaceAll("\"chunkExtractParallelism\":\\d+", "\"chunkExtractParallelism\":1.5")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replaceAll("\"language\":(\"([^\"]*)\"|null)", "\"language\":123")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        assertThatThrownBy(() -> OntologyJsonCodec.readContext(
            valid.replaceAll("\"enabled\":(true|false)", "\"enabled\":\"\"")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        String whitespaceEnabled = valid.replaceAll("\"enabled\":(true|false)", "\"enabled\":\" \\\\t\\\\n\"");
        assertThat(whitespaceEnabled).contains("\"enabled\":\" \\t\\n\"");
        assertThatThrownBy(() -> OntologyJsonCodec.readContext(whitespaceEnabled))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to deserialize ontology field");

        for (String field : List.of(
            "chunkExtractParallelism",
            "entityExtractMaxGleaning",
            "maxExtractInputTokens",
            "entityExtractMaxRecords",
            "entityExtractMaxEntities")) {
            assertThatThrownBy(() -> OntologyJsonCodec.readContext(
                valid.replaceAll("\"" + field + "\":\\d+", "\"" + field + "\":\"\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize ontology field");
            assertThatThrownBy(() -> OntologyJsonCodec.readContext(
                valid.replaceAll("\"" + field + "\":\\d+", "\"" + field + "\":\" \\\\t\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize ontology field");
        }

        assertThat(OntologyJsonCodec.readContext(valid).graphExtractionOptions())
            .isEqualTo(GraphExtractionOptions.defaults());
    }

    @Test
    void fingerprintChangesWithExtractionOptions() {
        var baseline = OntologyTaskContext.empty();
        var disabled = new OntologyTaskContext(
            GraphExtractionOptions.builder().enabled(false).build(),
            null,
            null,
            false
        );
        var reConfigured = new OntologyTaskContext(
            GraphExtractionOptions.builder().language("Chinese").build(),
            null,
            null,
            false
        );

        assertThat(disabled.fingerprint()).isNotEqualTo(baseline.fingerprint());
        assertThat(reConfigured.fingerprint()).isNotEqualTo(baseline.fingerprint());
        assertThat(disabled.fingerprint()).isNotEqualTo(reConfigured.fingerprint());
    }

    @Test
    void fingerprintChangesWithOverlayContent() {
        var ontology = new Ontology(
            List.of(new OntologyEntityType("Person", "A person", List.of(), null)),
            List.of(),
            null,
            Map.of()
        );
        var overlay = OntologyExtractionOverlay.from(ontology, ExtractionMode.STRICT);
        var withOverlay = new OntologyTaskContext(GraphExtractionOptions.defaults(), ontology, overlay, false);
        var withoutOverlay = new OntologyTaskContext(GraphExtractionOptions.defaults(), ontology, null, false);

        assertThat(withOverlay.fingerprint()).isNotEqualTo(withoutOverlay.fingerprint());
    }

    @Test
    void writeReadContextRoundTripPreservesSnapshot() {
        var ontology = new Ontology(
            List.of(
                new OntologyEntityType("Person", "A person", List.of(), null),
                new OntologyEntityType("Organization", "An organization", List.of(), null)
            ),
            List.of(new OntologyRelationType(
                "works at",
                "employment",
                List.of(new OntologyPattern("Person", "Organization")),
                List.of(),
                null
            )),
            null,
            Map.of("owner", "team")
        );
        var overlay = OntologyExtractionOverlay.from(ontology, ExtractionMode.PREFER);
        var options = GraphExtractionOptions.builder().language("Chinese").build();
        var context = new OntologyTaskContext(options, ontology, overlay, true);

        var restored = OntologyJsonCodec.readContext(OntologyJsonCodec.writeContext(context));

        assertThat(restored.graphExtractionOptions()).isEqualTo(options);
        assertThat(restored.dropOnUnknownKeyword()).isTrue();
        assertThat(restored.overlay()).isEqualTo(overlay);
        assertThat(restored.ontology().entityTypes())
            .extracting(OntologyEntityType::identityId)
            .containsExactlyInAnyOrderElementsOf(
                ontology.entityTypes().stream().map(OntologyEntityType::identityId).toList()
            );
        assertThat(restored.fingerprint()).isEqualTo(context.fingerprint());
    }
}
