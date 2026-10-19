package io.github.lightrag.rdf;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;

/**
 * Iterative RDF list walker with cycle detection and depth/node limits.
 *
 * <p>Cycles stop the current branch with a warning; exceeding the configured
 * depth or node limit aborts the import. No recursion is used, so a crafted
 * deeply nested document cannot overflow the stack.
 */
final class RdfListWalker {

    private final Model model;
    private final int maxDepth;
    private final int maxNodes;
    private final SortedSet<String> warnings;

    RdfListWalker(Model model, int maxDepth, int maxNodes, SortedSet<String> warnings) {
        this.model = Objects.requireNonNull(model, "model");
        this.maxDepth = maxDepth;
        this.maxNodes = maxNodes;
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /**
     * Reads a {@code rdf:first}/{@code rdf:rest} list.
     *
     * @param head  list head, may be {@code rdf:nil}
     * @param label description used in warnings
     * @return the elements in list order; {@code complete} is {@code false}
     *         when a cycle or malformed tail truncated the result
     */
    ReadResult read(Value head, String label) {
        List<Value> values = new ArrayList<>();
        if (head == null || RDF.NIL.equals(head)) {
            return new ReadResult(values, true);
        }

        Deque<Value> work = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        work.add(head);
        int nodes = 0;

        while (!work.isEmpty()) {
            Value current = work.removeFirst();
            if (RDF.NIL.equals(current)) {
                continue;
            }
            if (++nodes > maxNodes) {
                throw new OntologyImportException(
                    label + " traversal exceeds " + maxNodes + " nodes"
                );
            }
            if (!(current instanceof Resource resource)) {
                warnings.add("Malformed " + label + ": list tail is not a resource");
                return new ReadResult(values, false);
            }
            if (!visited.add(key(resource))) {
                warnings.add("Cycle detected while reading " + label + "; remaining elements are ignored");
                return new ReadResult(values, false);
            }
            if (values.size() >= maxDepth) {
                throw new OntologyImportException(
                    label + " exceeds the maximum depth of " + maxDepth
                );
            }

            List<Value> firsts = objects(resource, RDF.FIRST);
            if (firsts.size() != 1) {
                warnings.add("Malformed " + label + ": expected exactly one rdf:first, found " + firsts.size());
                return new ReadResult(values, false);
            }
            values.add(firsts.get(0));

            List<Value> rests = objects(resource, RDF.REST);
            if (rests.size() != 1) {
                warnings.add("Malformed " + label + ": expected exactly one rdf:rest, found " + rests.size());
                return new ReadResult(values, false);
            }
            Value rest = rests.get(0);
            if (!RDF.NIL.equals(rest)) {
                work.add(rest);
            }
        }
        return new ReadResult(values, true);
    }

    /** Result of a list traversal. */
    record ReadResult(List<Value> values, boolean complete) {
        ReadResult {
            values = List.copyOf(values);
        }
    }

    private List<Value> objects(Resource subject, IRI predicate) {
        List<Value> values = new ArrayList<>();
        for (var statement : model.filter(subject, predicate, null)) {
            values.add(statement.getObject());
        }
        values.sort(Comparator.comparing(RdfListWalker::key));
        return values;
    }

    private static String key(Value value) {
        if (value instanceof BNode bNode) {
            return "_:" + bNode.getID();
        }
        if (value instanceof Triple triple) {
            return "<<" + key(triple.getSubject()) + " " + triple.getPredicate().stringValue()
                + " " + key(triple.getObject()) + ">>";
        }
        return value.stringValue();
    }
}
