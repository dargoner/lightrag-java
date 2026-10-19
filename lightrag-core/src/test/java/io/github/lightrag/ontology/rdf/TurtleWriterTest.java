package io.github.lightrag.ontology.rdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TurtleWriterTest {

    @Test
    void rendersPrefixesCommentsAndTriplesInStableOrder() {
        TurtleWriter writer = new TurtleWriter()
            .prefix("rdfs", "http://www.w3.org/2000/01/rdf-schema#")
            .prefix("ex", "https://example.com/")
            .comment("second")
            .comment("first")
            .triple(
                TurtleWriter.iri("https://example.com/b"),
                TurtleWriter.prefixed("rdfs:label"),
                TurtleWriter.literal("B")
            )
            .triple(
                TurtleWriter.iri("https://example.com/a"),
                TurtleWriter.prefixed("a"),
                TurtleWriter.iri("https://example.com/Type")
            );

        String turtle = writer.render();

        assertThat(turtle.indexOf("@prefix ex:")).isLessThan(turtle.indexOf("@prefix rdfs:"));
        assertThat(turtle.indexOf("# first")).isLessThan(turtle.indexOf("# second"));
        assertThat(turtle.indexOf("<https://example.com/a>"))
            .isLessThan(turtle.indexOf("<https://example.com/b>"));
        assertThat(turtle).contains(
            "<https://example.com/a> a <https://example.com/Type> .",
            "<https://example.com/b> rdfs:label \"B\" ."
        );
    }

    @Test
    void supportsLanguageDatatypeBlankNodeAndListTerms() {
        TurtleWriter writer = new TurtleWriter()
            .prefix("xsd", TurtleWriter.XSD_NAMESPACE)
            .prefix("ex", "https://example.com/")
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:lang"),
                TurtleWriter.langLiteral("hello", "en-US")
            )
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:count"),
                TurtleWriter.typedLiteral("2", TurtleWriter.XSD_NAMESPACE + "integer")
            )
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:node"),
                TurtleWriter.blankNode("node1")
            )
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:list"),
                TurtleWriter.list(
                    TurtleWriter.literal("a"),
                    TurtleWriter.typedLiteral("1", TurtleWriter.XSD_NAMESPACE + "integer")
                )
            );

        assertThat(writer.render()).contains(
            "<https://example.com/s> ex:lang \"hello\"@en-US .",
            "<https://example.com/s> ex:count \"2\"^^xsd:integer .",
            "<https://example.com/s> ex:node _:node1 .",
            "<https://example.com/s> ex:list ( \"a\" \"1\"^^xsd:integer ) ."
        );
    }

    @Test
    void escapesQuotesBackslashesWhitespaceAndControlCharacters() {
        assertThat(TurtleWriter.escapeLiteral("a\"b\\c\nd\re\tf\bg\fh\u0001i"))
            .isEqualTo("a\\\"b\\\\c\\nd\\re\\tf\\bg\\fh\\u0001i");
    }

    @Test
    void rendersIdenticalInputIdentically() {
        TurtleWriter writer = new TurtleWriter()
            .prefix("ex", "https://example.com/")
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:p"),
                TurtleWriter.literal("v")
            );

        assertThat(writer.render()).isEqualTo(writer.render());
        assertThat(writer.render()).isEqualTo(new TurtleWriter()
            .prefix("ex", "https://example.com/")
            .triple(
                TurtleWriter.iri("https://example.com/s"),
                TurtleWriter.prefixed("ex:p"),
                TurtleWriter.literal("v")
            )
            .render());
    }
}
