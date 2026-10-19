package io.github.lightrag.rdf;

import no.hasmac.jsonld.JsonLdError;
import org.eclipse.rdf4j.rio.ParserConfig;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.rio.jsonld.JSONLDSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyRdfImportSecurityTest {

    private static final String REMOTE_CONTEXT_FIXTURE = """
        {
          "@context": "https://example.com/context.jsonld",
          "@id": "https://example.com/onto#Person",
          "@type": "http://www.w3.org/2002/07/owl#Class"
        }
        """;

    private static final String XxeFixture = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE rdf:RDF [
          <!ENTITY xxe SYSTEM "file:///etc/passwd">
        ]>
        <rdf:RDF
            xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
            xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#"
            xmlns:owl="http://www.w3.org/2002/07/owl#">
          <owl:Class rdf:about="https://example.com/onto#Leak">
            <rdfs:label>&xxe;</rdfs:label>
          </owl:Class>
        </rdf:RDF>
        """;

    @Test
    void xmlSecuritySettingsAreAppliedAndReadBack() {
        RDFParser parser = OntologyRdfImporter.createConfiguredParser(
            RdfImportFormat.RDFXML,
            RdfImportOptions.defaults()
        );
        ParserConfig config = parser.getParserConfig();

        assertThat(config.get(XMLParserSettings.SECURE_PROCESSING)).isTrue();
        assertThat(config.get(XMLParserSettings.DISALLOW_DOCTYPE_DECL)).isTrue();
        assertThat(config.get(XMLParserSettings.LOAD_EXTERNAL_DTD)).isFalse();
        assertThat(config.get(XMLParserSettings.EXTERNAL_GENERAL_ENTITIES)).isFalse();
        assertThat(config.get(XMLParserSettings.EXTERNAL_PARAMETER_ENTITIES)).isFalse();
        assertThat(config.get(XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS)).isTrue();
    }

    @Test
    void jsonLdSecuritySettingsAreAppliedAndReadBack() {
        RDFParser parser = OntologyRdfImporter.createConfiguredParser(
            RdfImportFormat.JSONLD,
            RdfImportOptions.defaults()
        );
        ParserConfig config = parser.getParserConfig();

        assertThat(config.get(JSONLDSettings.SECURE_MODE)).isTrue();
        assertThat(config.get(JSONLDSettings.WHITELIST)).isEmpty();
        assertThat(config.get(JSONLDSettings.DOCUMENT_LOADER_CACHE)).isFalse();
        assertThat(config.get(JSONLDSettings.EXCEPTION_ON_WARNING)).isTrue();
        assertThat(config.get(JSONLDSettings.DOCUMENT_LOADER))
            .isInstanceOf(SecureDocumentLoader.class);
    }

    @Test
    void autoFormatIsRejectedByTheParserFactory() {
        assertThatThrownBy(() -> OntologyRdfImporter.createConfiguredParser(
            RdfImportFormat.AUTO,
            RdfImportOptions.defaults()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("AUTO is not a concrete RDF parser format");
    }

    @Test
    void doctypeDeclarationsAreRejected() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(XxeFixture))
            .isInstanceOf(OntologyImportException.class)
            .satisfies(error -> assertThat(error.getMessage()).doesNotContain("root:"));
    }

    @Test
    void externalDtdReferencesAreRejected() {
        String fixture = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE rdf:RDF SYSTEM "http://127.0.0.1:1/evil.dtd">
            <rdf:RDF
                xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                xmlns:owl="http://www.w3.org/2002/07/owl#">
              <owl:Class rdf:about="https://example.com/onto#A"/>
            </rdf:RDF>
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(fixture))
            .isInstanceOf(OntologyImportException.class);
    }

    @Test
    void remoteJsonLdContextIsRejectedBeforeParsing() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(REMOTE_CONTEXT_FIXTURE))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Remote JSON-LD @context is not allowed");
    }

    @Test
    void remoteJsonLdContextInArrayIsRejected() {
        String fixture = """
            {
              "@context": [
                "https://example.com/context.jsonld",
                { "owl": "http://www.w3.org/2002/07/owl#" }
              ],
              "@id": "https://example.com/onto#A"
            }
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(fixture))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Remote JSON-LD @context is not allowed");
    }

    @Test
    void remoteJsonLdContextInNestedNodeIsRejected() {
        String fixture = """
            {
              "@context": { "owl": "http://www.w3.org/2002/07/owl#" },
              "@graph": [
                {
                  "@context": "http://evil.example/context.jsonld",
                  "@id": "https://example.com/onto#A"
                }
              ]
            }
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(fixture))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Remote JSON-LD @context is not allowed");
    }

    @Test
    void jsonLdImportIsRejected() {
        String fixture = """
            {
              "@context": { "@import": "https://example.com/context.jsonld" },
              "@id": "https://example.com/onto#A"
            }
            """;

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(fixture))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("JSON-LD @import is not allowed");
    }

    @Test
    void localJsonLdContextIsRejected(@TempDir Path tempDir) throws IOException {
        Path context = tempDir.resolve("context.jsonld");
        Files.writeString(context, """
            { "@context": { "@vocab": "https://example.com/onto#" } }
            """);
        String fixture = """
            {
              "@context": "%s",
              "@id": "Person",
              "@type": "http://www.w3.org/2002/07/owl#Class",
              "http://www.w3.org/2000/01/rdf-schema#label": "Person"
            }
            """.formatted(context.toUri());

        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(fixture))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Local or relative JSON-LD @context is not allowed");
    }

    @Test
    void wrongExplicitFormatIsRejected() {
        assertThatThrownBy(() -> OntologyRdfImporter.importOntology(
            """
                { "@context": { "owl": "http://www.w3.org/2002/07/owl#" },
                  "@id": "https://example.com/onto#A",
                  "@type": "owl:Class" }
                """,
            RdfImportOptions.builder().format(RdfImportFormat.RDFXML).build()
        ))
            .isInstanceOf(OntologyImportException.class)
            .hasMessageContaining("Failed to parse RDFXML document");
    }

    @Test
    void secureDocumentLoaderRejectsRemoteUris() {
        SecureDocumentLoader loader = new SecureDocumentLoader();

        assertThatThrownBy(() ->
            loader.loadDocument(URI.create("https://example.com/context.jsonld"), null)
        )
            .isInstanceOf(JsonLdError.class)
            .hasMessageContaining("JSON-LD document loading is disabled")
            .hasMessageContaining("https://example.com/context.jsonld");
    }

    @Test
    void secureDocumentLoaderRejectsLocalFiles(@TempDir Path tempDir) throws IOException {
        Path context = tempDir.resolve("context.jsonld");
        Files.writeString(context, """
            { "@context": { "@vocab": "https://example.com/onto#" } }
            """);

        assertThatThrownBy(() -> new SecureDocumentLoader().loadDocument(context.toUri(), null))
            .isInstanceOf(JsonLdError.class)
            .hasMessageContaining("JSON-LD document loading is disabled");
    }
}
