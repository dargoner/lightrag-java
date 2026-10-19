package io.github.lightrag.ontology.rdf;

import io.github.lightrag.ontology.Identity;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic, dependency-free Turtle/OWL/SHACL exporter for
 * {@link Ontology} snapshots.
 *
 * <p>The export is intentionally lossless for the supported subset:
 *
 * <ul>
 *   <li>entity types become {@code owl:Class} with label/comment;</li>
 *   <li>relation types become {@code owl:ObjectProperty}; a single pattern is
 *       written as {@code rdfs:domain}/{@code rdfs:range}, multiple patterns
 *       become concrete {@code lg:RelationPattern} nodes;</li>
 *   <li>every entity property is owner-scoped as an
 *       {@code owl:DatatypeProperty} with both {@code lg:owner} and
 *       {@code rdfs:domain}; no shared or union domains are produced;</li>
 *   <li>every relation property is an owner-scoped
 *       {@code lg:RelationAttribute} node;</li>
 *   <li>source bindings and versions are emitted as LightRAG extension
 *       terms;</li>
 *   <li>SHACL shapes cover entity properties only and never invent LIST
 *       constraints.</li>
 * </ul>
 *
 * <p>All declarations are ordered by {@link Identity#identityId(String)}.
 * Identical duplicate property declarations are deduplicated; any differing
 * declaration for the same {@code (kind, ownerIdentity, propertyIdentity)}
 * raises {@link OntologyRdfExportException}. No timestamps, random identifiers,
 * or hash-based IRI fallbacks are produced.
 */
public final class OntologyRdfExporter {

    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS_NS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL_NS = "http://www.w3.org/2002/07/owl#";
    private static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";
    private static final String SH_NS = "http://www.w3.org/ns/shacl#";

    private static final String XSD_INTEGER = XSD_NS + "integer";
    private static final String XSD_BOOLEAN = XSD_NS + "boolean";

    private static final String DEFINITION_HASH_KEY = "definitionHash";
    private static final int MAX_LITERAL_BYTES = 64 * 1024;

    /** Creates a stateless exporter. */
    public OntologyRdfExporter() {}

    /**
     * Exports the ontology as Turtle. SHACL shapes are appended when
     * {@link RdfExportOptions#includeShacl()} is enabled.
     */
    public String export(Ontology ontology, RdfExportOptions options) {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(options, "options");

        RdfIriEncoder encoder = newEncoder(options);
        Set<String> generatedIris = new TreeSet<>();
        List<EntityView> entities = buildEntities(ontology, options, encoder, generatedIris);
        List<RelationView> relations = buildRelations(ontology, options, encoder, generatedIris);

        if (generatedIris.contains(options.ontologyIri())) {
            throw new OntologyRdfExportException(
                "ontologyIri collides with a generated vocabulary IRI: " + options.ontologyIri()
            );
        }

        TurtleWriter writer = newWriter(options);
        writeOntologyHeader(writer, ontology, options);
        writeEntities(writer, entities, options, encoder);
        writeRelations(writer, relations, options, encoder);
        if (options.includeShacl()) {
            writeShacl(writer, entities, encoder);
        }
        return writer.render();
    }

    /**
     * Exports only SHACL shapes for the entity properties. This method always
     * emits shapes; {@link RdfExportOptions#includeShacl()} only controls whether
     * {@link #export(Ontology, RdfExportOptions)} appends them.
     */
    public String exportShacl(Ontology ontology, RdfExportOptions options) {
        Objects.requireNonNull(ontology, "ontology");
        Objects.requireNonNull(options, "options");

        RdfIriEncoder encoder = newEncoder(options);
        Set<String> generatedIris = new TreeSet<>();
        List<EntityView> entities = buildEntities(ontology, options, encoder, generatedIris);

        TurtleWriter writer = newWriter(options);
        writeShacl(writer, entities, encoder);
        return writer.render();
    }

    /** Static convenience alias for {@link #export(Ontology, RdfExportOptions)}. */
    public static String exportTurtle(Ontology ontology, RdfExportOptions options) {
        return new OntologyRdfExporter().export(ontology, options);
    }

    /** Static convenience alias for {@link #exportShacl(Ontology, RdfExportOptions)}. */
    public static String exportShaclTurtle(Ontology ontology, RdfExportOptions options) {
        return new OntologyRdfExporter().exportShacl(ontology, options);
    }

    private static RdfIriEncoder newEncoder(RdfExportOptions options) {
        checkIri(options.ontologyIri(), "ontologyIri");
        checkIri(options.vocabularyBase(), "vocabularyBase");
        checkIri(options.shapesBase(), "shapesBase");
        return new RdfIriEncoder(options.vocabularyBase(), options.shapesBase());
    }

    private static TurtleWriter newWriter(RdfExportOptions options) {
        return new TurtleWriter()
            .prefix("rdf", RDF_NS)
            .prefix("rdfs", RDFS_NS)
            .prefix("owl", OWL_NS)
            .prefix("xsd", XSD_NS)
            .prefix("sh", SH_NS)
            .prefix(options.prefix(), options.vocabularyBase());
    }

    // ------------------------------------------------------------------
    // Model construction (sorted, conflict-checked)
    // ------------------------------------------------------------------

    private static List<EntityView> buildEntities(
        Ontology ontology,
        RdfExportOptions options,
        RdfIriEncoder encoder,
        Set<String> generatedIris
    ) {
        List<OntologyEntityType> sorted = new ArrayList<>(ontology.entityTypes());
        sorted.sort(Comparator.comparing(OntologyEntityType::identityId));

        List<EntityView> views = new ArrayList<>(sorted.size());
        for (OntologyEntityType entity : sorted) {
            String identity = entity.identityId();
            String iri = encoder.entityClass(identity);
            String shaclIri = encoder.shaclNode(identity);
            generatedIris.add(iri);
            generatedIris.add(shaclIri);
            addBindingIri(encoder, options, generatedIris, iri, entity.sourceBinding());

            List<EntityAttribute> attributes = buildEntityAttributes(
                identity, entity.properties(), options, encoder, generatedIris
            );
            views.add(new EntityView(identity, entity, iri, shaclIri, attributes));
        }
        return List.copyOf(views);
    }

    private static List<EntityAttribute> buildEntityAttributes(
        String ownerIdentity,
        List<OntologyProperty> properties,
        RdfExportOptions options,
        RdfIriEncoder encoder,
        Set<String> generatedIris
    ) {
        Map<String, OntologyProperty> unique = new TreeMap<>();
        for (OntologyProperty property : properties) {
            String identity = property.identityId();
            OntologyProperty previous = unique.get(identity);
            if (previous == null) {
                unique.put(identity, property);
            } else if (!sameProperty(previous, property)) {
                throw propertyConflict("entity attribute", ownerIdentity, identity, previous, property);
            }
        }

        List<EntityAttribute> attributes = new ArrayList<>(unique.size());
        for (Map.Entry<String, OntologyProperty> entry : unique.entrySet()) {
            String iri = encoder.entityAttribute(ownerIdentity, entry.getKey());
            String shaclIri = encoder.shaclPropertyNode(ownerIdentity, entry.getKey());
            generatedIris.add(iri);
            generatedIris.add(shaclIri);
            addBindingIri(encoder, options, generatedIris, iri, entry.getValue().sourceBinding());
            attributes.add(new EntityAttribute(
                ownerIdentity,
                entry.getKey(),
                entry.getValue(),
                iri,
                shaclIri
            ));
        }
        return List.copyOf(attributes);
    }

    private static List<RelationView> buildRelations(
        Ontology ontology,
        RdfExportOptions options,
        RdfIriEncoder encoder,
        Set<String> generatedIris
    ) {
        List<OntologyRelationType> sorted = new ArrayList<>(ontology.relationTypes());
        sorted.sort(Comparator.comparing(OntologyRelationType::identityId));

        List<RelationView> views = new ArrayList<>(sorted.size());
        for (OntologyRelationType relation : sorted) {
            String identity = relation.identityId();
            String iri = encoder.relationProperty(identity);
            generatedIris.add(iri);
            addBindingIri(encoder, options, generatedIris, iri, relation.sourceBinding());

            List<PatternView> patterns = buildPatterns(identity, relation.patterns(), encoder, generatedIris);
            List<RelationAttribute> attributes = buildRelationAttributes(
                identity, relation.properties(), options, encoder, generatedIris
            );
            views.add(new RelationView(identity, relation, iri, patterns, attributes));
        }
        return List.copyOf(views);
    }

    private static List<PatternView> buildPatterns(
        String relationIdentity,
        List<OntologyPattern> patterns,
        RdfIriEncoder encoder,
        Set<String> generatedIris
    ) {
        Map<String, OntologyPattern> unique = new TreeMap<>();
        for (OntologyPattern pattern : patterns) {
            unique.putIfAbsent(pattern.identityId(), pattern);
        }

        List<PatternView> views = new ArrayList<>(unique.size());
        for (OntologyPattern pattern : unique.values()) {
            String sourceIdentity = Identity.identityId(pattern.sourceLabel());
            String targetIdentity = Identity.identityId(pattern.targetLabel());
            String sourceClassIri = encoder.entityClass(sourceIdentity);
            String targetClassIri = encoder.entityClass(targetIdentity);
            String nodeIri = encoder.relationPatternNode(
                relationIdentity, sourceIdentity, targetIdentity
            );
            generatedIris.add(sourceClassIri);
            generatedIris.add(targetClassIri);
            generatedIris.add(nodeIri);
            views.add(new PatternView(
                sourceIdentity,
                targetIdentity,
                nodeIri,
                sourceClassIri,
                targetClassIri
            ));
        }
        return List.copyOf(views);
    }

    private static List<RelationAttribute> buildRelationAttributes(
        String ownerIdentity,
        List<OntologyProperty> properties,
        RdfExportOptions options,
        RdfIriEncoder encoder,
        Set<String> generatedIris
    ) {
        Map<String, OntologyProperty> unique = new TreeMap<>();
        for (OntologyProperty property : properties) {
            String identity = property.identityId();
            OntologyProperty previous = unique.get(identity);
            if (previous == null) {
                unique.put(identity, property);
            } else if (!sameProperty(previous, property)) {
                throw propertyConflict("relation attribute", ownerIdentity, identity, previous, property);
            }
        }

        List<RelationAttribute> attributes = new ArrayList<>(unique.size());
        for (Map.Entry<String, OntologyProperty> entry : unique.entrySet()) {
            String iri = encoder.relationAttribute(ownerIdentity, entry.getKey());
            generatedIris.add(iri);
            addBindingIri(encoder, options, generatedIris, iri, entry.getValue().sourceBinding());
            attributes.add(new RelationAttribute(
                ownerIdentity,
                entry.getKey(),
                entry.getValue(),
                iri
            ));
        }
        return List.copyOf(attributes);
    }

    private static void addBindingIri(
        RdfIriEncoder encoder,
        RdfExportOptions options,
        Set<String> generatedIris,
        String subjectIri,
        SourceBinding binding
    ) {
        if (options.includeSourceBindings() && binding != null) {
            generatedIris.add(encoder.sourceBindingNode(subjectIri));
        }
    }

    private static boolean sameProperty(OntologyProperty a, OntologyProperty b) {
        return Objects.equals(a.name(), b.name())
            && a.type() == b.type()
            && Objects.equals(a.description(), b.description())
            && Objects.equals(a.sourceBinding(), b.sourceBinding());
    }

    private static OntologyRdfExportException propertyConflict(
        String kind,
        String ownerIdentity,
        String propertyIdentity,
        OntologyProperty a,
        OntologyProperty b
    ) {
        List<String> differing = new ArrayList<>();
        if (!Objects.equals(a.name(), b.name())) {
            differing.add("display name");
        }
        if (a.type() != b.type()) {
            differing.add("valueType");
        }
        if (!Objects.equals(a.description(), b.description())) {
            differing.add("description");
        }
        if (!Objects.equals(a.sourceBinding(), b.sourceBinding())) {
            differing.add("sourceBinding");
        }
        return new OntologyRdfExportException(
            "Conflicting " + kind + " declaration for owner '" + ownerIdentity
                + "' and property '" + propertyIdentity + "': "
                + String.join(", ", differing) + " differ"
        );
    }

    // ------------------------------------------------------------------
    // Turtle emission
    // ------------------------------------------------------------------

    private static void writeOntologyHeader(
        TurtleWriter writer,
        Ontology ontology,
        RdfExportOptions options
    ) {
        writer.triple(
            TurtleWriter.iri(options.ontologyIri()),
            TurtleWriter.prefixed("a"),
            TurtleWriter.prefixed("owl:Ontology")
        );
        if (!options.includeVersion()) {
            return;
        }
        if (ontology.version() != null && !ontology.version().isEmpty()) {
            writeLiteral(
                writer,
                options.ontologyIri(),
                "owl:versionInfo",
                ontology.version(),
                "ontology version"
            );
            writeLiteral(
                writer,
                options.ontologyIri(),
                options.prefix() + ":version",
                ontology.version(),
                "ontology version"
            );
        }
        String definitionHash = ontology.metadata().get(DEFINITION_HASH_KEY);
        if (definitionHash != null && !definitionHash.isBlank()) {
            writeLiteral(
                writer,
                options.ontologyIri(),
                options.prefix() + ":definitionHash",
                definitionHash,
                "ontology definitionHash"
            );
        }
    }

    private static void writeEntities(
        TurtleWriter writer,
        List<EntityView> entities,
        RdfExportOptions options,
        RdfIriEncoder encoder
    ) {
        for (EntityView entity : entities) {
            writer.triple(
                TurtleWriter.iri(entity.iri()),
                TurtleWriter.prefixed("a"),
                TurtleWriter.prefixed("owl:Class")
            );
            writeLiteral(writer, entity.iri(), "rdfs:label", entity.type().label(), "entity label");
            if (entity.type().description() != null) {
                writeLiteral(
                    writer,
                    entity.iri(),
                    "rdfs:comment",
                    entity.type().description(),
                    "entity description"
                );
            }
            writeBinding(writer, options, encoder, entity.iri(), entity.type().sourceBinding());

            for (EntityAttribute attribute : entity.attributes()) {
                writer.triple(
                    TurtleWriter.iri(attribute.iri()),
                    TurtleWriter.prefixed("a"),
                    TurtleWriter.prefixed("owl:DatatypeProperty")
                );
                writeLiteral(
                    writer,
                    attribute.iri(),
                    "rdfs:label",
                    attribute.property().name(),
                    "entity property label"
                );
                writeIri(writer, attribute.iri(), options.prefix() + ":owner", entity.iri());
                writeIri(writer, attribute.iri(), "rdfs:domain", entity.iri());
                writeLiteral(
                    writer,
                    attribute.iri(),
                    options.prefix() + ":valueType",
                    attribute.property().type().name(),
                    "entity property valueType"
                );
                String range = rangeType(attribute.property().type());
                if (range != null) {
                    writer.triple(
                        TurtleWriter.iri(attribute.iri()),
                        TurtleWriter.prefixed("rdfs:range"),
                        TurtleWriter.prefixed(range)
                    );
                }
                if (attribute.property().description() != null) {
                    writeLiteral(
                        writer,
                        attribute.iri(),
                        "rdfs:comment",
                        attribute.property().description(),
                        "entity property description"
                    );
                }
                writeBinding(
                    writer,
                    options,
                    encoder,
                    attribute.iri(),
                    attribute.property().sourceBinding()
                );
            }
        }
    }

    private static void writeRelations(
        TurtleWriter writer,
        List<RelationView> relations,
        RdfExportOptions options,
        RdfIriEncoder encoder
    ) {
        for (RelationView relation : relations) {
            writer.triple(
                TurtleWriter.iri(relation.iri()),
                TurtleWriter.prefixed("a"),
                TurtleWriter.prefixed("owl:ObjectProperty")
            );
            writeLiteral(writer, relation.iri(), "rdfs:label", relation.type().label(), "relation label");
            if (relation.type().description() != null) {
                writeLiteral(
                    writer,
                    relation.iri(),
                    "rdfs:comment",
                    relation.type().description(),
                    "relation description"
                );
            }

            if (relation.patterns().size() == 1) {
                PatternView pattern = relation.patterns().get(0);
                writeIri(writer, relation.iri(), "rdfs:domain", pattern.sourceClassIri());
                writeIri(writer, relation.iri(), "rdfs:range", pattern.targetClassIri());
            } else if (relation.patterns().size() > 1) {
                for (PatternView pattern : relation.patterns()) {
                    writeIri(
                        writer,
                        relation.iri(),
                        options.prefix() + ":relationPattern",
                        pattern.nodeIri()
                    );
                    writer.triple(
                        TurtleWriter.iri(pattern.nodeIri()),
                        TurtleWriter.prefixed("a"),
                        TurtleWriter.prefixed(options.prefix() + ":RelationPattern")
                    );
                    writeIri(
                        writer,
                        pattern.nodeIri(),
                        options.prefix() + ":relation",
                        relation.iri()
                    );
                    writeIri(
                        writer,
                        pattern.nodeIri(),
                        options.prefix() + ":sourceClass",
                        pattern.sourceClassIri()
                    );
                    writeIri(
                        writer,
                        pattern.nodeIri(),
                        options.prefix() + ":targetClass",
                        pattern.targetClassIri()
                    );
                }
            }

            for (RelationAttribute attribute : relation.attributes()) {
                writer.triple(
                    TurtleWriter.iri(attribute.iri()),
                    TurtleWriter.prefixed("a"),
                    TurtleWriter.prefixed(options.prefix() + ":RelationAttribute")
                );
                writeIri(writer, attribute.iri(), options.prefix() + ":owner", relation.iri());
                writeLiteral(
                    writer,
                    attribute.iri(),
                    options.prefix() + ":attributeName",
                    attribute.property().name(),
                    "relation property name"
                );
                writeLiteral(
                    writer,
                    attribute.iri(),
                    options.prefix() + ":valueType",
                    attribute.property().type().name(),
                    "relation property valueType"
                );
                if (attribute.property().description() != null) {
                    writeLiteral(
                        writer,
                        attribute.iri(),
                        options.prefix() + ":description",
                        attribute.property().description(),
                        "relation property description"
                    );
                }
                writeBinding(
                    writer,
                    options,
                    encoder,
                    attribute.iri(),
                    attribute.property().sourceBinding()
                );
            }

            writeBinding(writer, options, encoder, relation.iri(), relation.type().sourceBinding());
        }
    }

    private static void writeShacl(
        TurtleWriter writer,
        List<EntityView> entities,
        RdfIriEncoder encoder
    ) {
        for (EntityView entity : entities) {
            writer.triple(
                TurtleWriter.iri(entity.shaclIri()),
                TurtleWriter.prefixed("a"),
                TurtleWriter.prefixed("sh:NodeShape")
            );
            writeIri(writer, entity.shaclIri(), "sh:targetClass", entity.iri());

            for (EntityAttribute attribute : entity.attributes()) {
                String propertyShape = attribute.shaclIri();
                writeIri(writer, entity.shaclIri(), "sh:property", propertyShape);
                writer.triple(
                    TurtleWriter.iri(propertyShape),
                    TurtleWriter.prefixed("a"),
                    TurtleWriter.prefixed("sh:PropertyShape")
                );
                writeIri(writer, propertyShape, "sh:path", attribute.iri());

                String datatype = rangeType(attribute.property().type());
                if (datatype == null) {
                    writer.comment(
                        "LIST property " + attribute.iri()
                            + " is exported as a JSON array; no SHACL datatype is asserted."
                    );
                } else {
                    writer.triple(
                        TurtleWriter.iri(propertyShape),
                        TurtleWriter.prefixed("sh:datatype"),
                        TurtleWriter.prefixed(datatype)
                    );
                }
                writer.typedLiteral(
                    propertyShape,
                    TurtleWriter.prefixed("sh:maxCount"),
                    "1",
                    XSD_INTEGER
                );
            }
        }
    }

    private static void writeBinding(
        TurtleWriter writer,
        RdfExportOptions options,
        RdfIriEncoder encoder,
        String ownerIri,
        SourceBinding binding
    ) {
        if (!options.includeSourceBindings() || binding == null) {
            return;
        }
        String node = encoder.sourceBindingNode(ownerIri);
        writeIri(writer, ownerIri, options.prefix() + ":sourceBinding", node);
        writer.triple(
            TurtleWriter.iri(node),
            TurtleWriter.prefixed("a"),
            TurtleWriter.prefixed(options.prefix() + ":SourceBinding")
        );
        if (binding.tableName() != null) {
            writeLiteral(
                writer, node, options.prefix() + ":tableName", binding.tableName(), "source tableName"
            );
        }
        if (binding.column() != null) {
            writeLiteral(
                writer, node, options.prefix() + ":column", binding.column(), "source column"
            );
        }
        if (binding.refTableName() != null) {
            writeLiteral(
                writer,
                node,
                options.prefix() + ":refTableName",
                binding.refTableName(),
                "source refTableName"
            );
        }
        if (binding.refColumn() != null) {
            writeLiteral(
                writer,
                node,
                options.prefix() + ":refColumn",
                binding.refColumn(),
                "source refColumn"
            );
        }
        if (binding.nullable() != null) {
            writer.typedLiteral(
                node,
                TurtleWriter.prefixed(options.prefix() + ":nullable"),
                binding.nullable().toString(),
                XSD_BOOLEAN
            );
        }
    }

    private static void writeLiteral(
        TurtleWriter writer,
        String subjectIri,
        String predicate,
        String value,
        String field
    ) {
        checkLiteral(value, field);
        writer.literal(subjectIri, TurtleWriter.prefixed(predicate), value);
    }

    private static void writeIri(
        TurtleWriter writer,
        String subjectIri,
        String predicate,
        String objectIri
    ) {
        checkIri(subjectIri, "subject IRI");
        checkIri(objectIri, "object IRI");
        writer.triple(
            TurtleWriter.iri(subjectIri),
            TurtleWriter.prefixed(predicate),
            TurtleWriter.iri(objectIri)
        );
    }

    private static String rangeType(OntologyPropertyType type) {
        return switch (type) {
            case STRING -> "xsd:string";
            case INTEGER -> "xsd:integer";
            case FLOAT -> "xsd:double";
            case BOOLEAN -> "xsd:boolean";
            case DATE -> "xsd:date";
            case LIST -> null;
        };
    }

    private static void checkIri(String iri, String field) {
        int length = RdfIriEncoder.utf8Length(iri);
        if (length > RdfIriEncoder.MAX_IRI_BYTES) {
            throw new OntologyRdfExportException(
                field + " exceeds " + RdfIriEncoder.MAX_IRI_BYTES
                    + " UTF-8 bytes (" + length + "): " + abbreviate(iri)
            );
        }
    }

    private static void checkLiteral(String value, String field) {
        int length = RdfIriEncoder.utf8Length(value);
        if (length > MAX_LITERAL_BYTES) {
            throw new OntologyRdfExportException(
                field + " exceeds " + MAX_LITERAL_BYTES
                    + " UTF-8 bytes (" + length + "): " + abbreviate(value)
            );
        }
    }

    private static String abbreviate(String value) {
        return value.length() <= 128 ? value : value.substring(0, 128) + "...";
    }

    // ------------------------------------------------------------------
    // Internal model
    // ------------------------------------------------------------------

    private record EntityView(
        String identity,
        OntologyEntityType type,
        String iri,
        String shaclIri,
        List<EntityAttribute> attributes
    ) {}

    private record EntityAttribute(
        String ownerIdentity,
        String propertyIdentity,
        OntologyProperty property,
        String iri,
        String shaclIri
    ) {}

    private record RelationView(
        String identity,
        OntologyRelationType type,
        String iri,
        List<PatternView> patterns,
        List<RelationAttribute> attributes
    ) {}

    private record RelationAttribute(
        String ownerIdentity,
        String propertyIdentity,
        OntologyProperty property,
        String iri
    ) {}

    private record PatternView(
        String sourceIdentity,
        String targetIdentity,
        String nodeIri,
        String sourceClassIri,
        String targetClassIri
    ) {}
}
