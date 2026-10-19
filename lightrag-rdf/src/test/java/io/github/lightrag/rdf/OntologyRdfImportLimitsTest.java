package io.github.lightrag.rdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyRdfImportLimitsTest {

    private static final String UNION_FIXTURE = """
        @prefix : <https://example.com/onto#> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        :Person a owl:Class ; rdfs:label "Person" .
        :Org a owl:Class ; rdfs:label "Org" .
        :p a owl:DatatypeProperty ; rdfs:label "p" ;
            rdfs:domain [ owl:unionOf ( :Person :Org ) ] .
        """;

    @Test
    void maxBytesIsEnforcedBeforeParsing() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxBytes(20).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("maximum of 20 UTF-8 bytes");
    }

    @Test
    void pathEntryReadsAValidFile(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("onto.ttl");
        Files.writeString(file, UNION_FIXTURE);

        ImportResult result = OntologyRdfImporter.importOntology(file, RdfImportOptions.defaults());

        assertThat(result.ontology().entityTypes()).hasSize(2);
    }

    @Test
    void pathEntryAbortsOnceTheByteLimitIsExceeded(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("onto.ttl");
        Files.writeString(file, UNION_FIXTURE);

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            file,
            RdfImportOptions.builder().maxBytes(20).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("exceeds the maximum");
    }

    @Test
    void boundedReaderStopsAtTheFirstChunkThatExceedsTheLimit() throws IOException {
        byte[] payload = new byte[64 * 1024];
        Arrays.fill(payload, (byte) ' ');
        CountingInputStream input = new CountingInputStream(payload);

        assertThatThrownBy(() -> OntologyRdfImporter.readBounded(
            input,
            20,
            Path.of("large.ttl")
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("exceeds the maximum");

        assertThat(input.bytesRead())
            .isEqualTo(8192)
            .isLessThan(payload.length);
        assertThat(input.exhausted()).isFalse();
    }

    private static final class CountingInputStream extends InputStream {

        private final byte[] data;
        private int position;

        private CountingInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (position == data.length) {
                return -1;
            }
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position == data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, target, offset, count);
            position += count;
            return count;
        }

        private int bytesRead() {
            return position;
        }

        private boolean exhausted() {
            return position == data.length;
        }
    }

    @Test
    void maxTriplesAbortsParsing() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxTriples(2).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("statement limit exceeded");
    }

    @Test
    void maxLiteralBytesIsEnforcedPerStatement() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxLiteralBytes(3).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Literal exceeds 3 UTF-8 bytes");
    }

    @Test
    void maxIriBytesIsEnforcedPerStatement() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxIriBytes(10).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("IRI exceeds 10 UTF-8 bytes");
    }

    @Test
    void maxDepthIsEnforcedWhileWalkingRdfLists() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxDepth(1).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("maximum depth of 1");
    }

    @Test
    void maxNodesIsEnforcedWhileWalkingRdfLists() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            UNION_FIXTURE,
            RdfImportOptions.builder().maxNodes(1).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("traversal exceeds 1 nodes");
    }

    @Test
    void malformedListIsSkippedWithAWarning() {
        ImportResult result = OntologyRdfImporter.importOntology("""
            @prefix : <https://example.com/onto#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            :Person a owl:Class ; rdfs:label "Person" .
            :p a owl:DatatypeProperty ; rdfs:label "p" ;
                rdfs:domain [ owl:unionOf _:l1 ] .
            _:l1 rdf:first :Person .
            """);

        assertThat(result.ontology().entityTypes().get(0).properties()).isEmpty();
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("expected exactly one rdf:rest"));
        assertThat(result.warnings())
            .anyMatch(warning -> warning.contains("incomplete or cyclic"));
    }
}
