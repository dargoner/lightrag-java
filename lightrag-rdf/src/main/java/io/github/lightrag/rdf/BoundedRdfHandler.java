package io.github.lightrag.rdf;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

/**
 * Counting RDF handler that aborts parsing as soon as a configured limit is
 * exceeded. Statements are streamed into a model; the limit check happens per
 * statement, never after building the full model.
 */
final class BoundedRdfHandler extends AbstractRDFHandler {

    private final LinkedHashModel model = new LinkedHashModel();
    private final int maxTriples;
    private final int maxLiteralBytes;
    private final int maxIriBytes;
    private int count;

    BoundedRdfHandler(int maxTriples, int maxLiteralBytes, int maxIriBytes) {
        this.maxTriples = maxTriples;
        this.maxLiteralBytes = maxLiteralBytes;
        this.maxIriBytes = maxIriBytes;
    }

    @Override
    public void handleStatement(Statement statement) {
        if (++count > maxTriples) {
            throw new OntologyImportException(
                "RDF statement limit exceeded: more than " + maxTriples + " statements"
            );
        }
        checkValue(statement.getSubject());
        checkValue(statement.getPredicate());
        checkValue(statement.getObject());
        model.add(statement);
    }

    LinkedHashModel model() {
        return model;
    }

    int statementCount() {
        return count;
    }

    private void checkValue(Value value) {
        if (value instanceof IRI iri) {
            checkLength(iri.stringValue(), maxIriBytes, "IRI");
        } else if (value instanceof Literal literal) {
            checkLength(literal.getLabel(), maxLiteralBytes, "Literal");
            if (literal.getDatatype() != null) {
                checkLength(literal.getDatatype().stringValue(), maxIriBytes, "IRI");
            }
        } else if (value instanceof Triple triple) {
            checkValue(triple.getSubject());
            checkValue(triple.getPredicate());
            checkValue(triple.getObject());
        }
    }

    private static void checkLength(String value, int limit, String kind) {
        long length = RdfIriSupport.utf8Length(value);
        if (length > limit) {
            throw new OntologyImportException(
                kind + " exceeds " + limit + " UTF-8 bytes (" + length + "): " + abbreviate(value)
            );
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }
}
