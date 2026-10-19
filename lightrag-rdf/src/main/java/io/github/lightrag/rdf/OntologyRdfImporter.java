package io.github.lightrag.rdf;

import io.github.lightrag.ontology.Identity;
import io.github.lightrag.ontology.Ontology;
import io.github.lightrag.ontology.OntologyEntityType;
import io.github.lightrag.ontology.OntologyPattern;
import io.github.lightrag.ontology.OntologyProperty;
import io.github.lightrag.ontology.OntologyPropertyType;
import io.github.lightrag.ontology.OntologyRelationType;
import io.github.lightrag.ontology.SourceBinding;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonStructure;
import jakarta.json.JsonValue;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParseException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.RioSetting;
import org.eclipse.rdf4j.rio.helpers.XMLParserSettings;
import org.eclipse.rdf4j.rio.jsonld.JSONLDSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * RDF4J-based importer for Turtle, RDF/XML and JSON-LD ontology documents.
 *
 * <p>The importer maps the supported RDF subset onto the dependency-free
 * {@link Ontology} model used by {@code lightrag-core}:
 *
 * <ul>
 *   <li>{@code owl:Class}/{@code rdfs:Class} become entity types;</li>
 *   <li>{@code owl:ObjectProperty} becomes a relation type with
 *       {@code rdfs:domain}/{@code rdfs:range} or {@code lg:relationPattern}
 *       patterns;</li>
 *   <li>{@code owl:DatatypeProperty} becomes an owner-scoped entity property
 *       ({@code lg:owner}, falling back to {@code rdfs:domain});</li>
 *   <li>{@code lg:RelationAttribute} becomes an owner-scoped relation property;</li>
 *   <li>{@code lg:SourceBinding}, {@code lg:version} and
 *       {@code lg:definitionHash} are imported when present.</li>
 * </ul>
 *
 * <p>Unsupported axioms (subclass relations, restrictions, equivalence,
 * cardinality, {@code owl:imports}, SHACL shapes, ...) are ignored; when
 * {@link RdfImportOptions#allowUnknownAxioms()} is enabled a deterministic
 * warning is recorded. No reasoner is used, no remote document is fetched, and
 * the result is a draft: the importer never assigns a version or applies
 * governance changes.
 */
public final class OntologyRdfImporter {

    /** Base IRI used to resolve relative IRIs when no explicit base is supplied. */
    static final String DEFAULT_BASE_IRI = "https://lightrag.invalid/rdf/import/";

    private static final ValueFactory VALUE_FACTORY = SimpleValueFactory.getInstance();

    private OntologyRdfImporter() {}

    /**
     * Imports an ontology from RDF content.
     *
     * @param content the RDF document; must not be {@code null}
     * @param options import options; must not be {@code null}
     * @return the imported ontology, sorted warnings and statement counts
     * @throws OntologyImportException when the document is malformed, exceeds a
     *                                 limit, uses a rejected security feature,
     *                                 or contains conflicting declarations
     */
    public static ImportResult importOntology(String content, RdfImportOptions options) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(options, "options");
        content = stripBom(content);

        enforceMaxBytes(content, options.maxBytes());
        RdfImportFormat format = options.format() == RdfImportFormat.AUTO
            ? detectFormat(content)
            : options.format();

        if (format == RdfImportFormat.JSONLD) {
            preflightJsonLd(content);
        }

        BoundedRdfHandler handler = new BoundedRdfHandler(
            options.maxTriples(),
            options.maxLiteralBytes(),
            options.maxIriBytes()
        );
        RDFParser parser = createConfiguredParser(format, options);
        parser.setRDFHandler(handler);

        String baseIri = options.baseIri() == null ? DEFAULT_BASE_IRI : options.baseIri();
        try {
            parser.parse(new StringReader(content), baseIri);
        } catch (OntologyImportException e) {
            throw e;
        } catch (RDFParseException | RDFHandlerException e) {
            throw unwrapImportException(e)
                .orElseGet(() -> new OntologyImportException(
                    "Failed to parse " + format + " document: " + e.getMessage(), e
                ));
        } catch (IOException e) {
            throw new OntologyImportException("Failed to read RDF document: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw unwrapImportException(e)
                .orElseGet(() -> new OntologyImportException(
                    "Failed to parse " + format + " document: " + e.getMessage(), e
                ));
        }

        SortedSet<String> warnings = new TreeSet<>();
        Mapper mapper = new Mapper(
            handler.model(),
            handler.statementCount(),
            options,
            warnings
        );
        return mapper.map();
    }

    /**
     * Imports an ontology from a local RDF file, aborting once the configured
     * byte limit is exceeded instead of reading the whole file first.
     *
     * @param path    the RDF document path; must not be {@code null}
     * @param options import options; must not be {@code null}
     * @return the imported ontology, sorted warnings and statement counts
     * @throws OntologyImportException when the file cannot be read, exceeds the
     *                                 byte limit, or the content is invalid
     */
    public static ImportResult importOntology(Path path, RdfImportOptions options) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(options, "options");
        return importOntology(readBounded(path, options.maxBytes()), options);
    }

    private static String readBounded(Path path, long maxBytes) {
        try (InputStream input = Files.newInputStream(path)) {
            return readBounded(input, maxBytes, path);
        } catch (IOException e) {
            throw new OntologyImportException(
                "Failed to read RDF document " + path + ": " + e.getMessage(), e
            );
        }
    }

    static String readBounded(InputStream input, long maxBytes, Path source) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new OntologyImportException(
                    "RDF input exceeds the maximum of " + maxBytes
                        + " UTF-8 bytes: " + source
                );
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Imports an ontology using {@link RdfImportOptions#defaults()}.
     */
    public static ImportResult importOntology(String content) {
        return importOntology(content, RdfImportOptions.defaults());
    }

    /**
     * Creates a parser with the security settings required by the plan.
     *
     * <p>Package-private so the security read-back assertions can be tested
     * without exposing RDF4J in the public API. Every setting is applied and
     * then read back; if a setting is unsupported or ignored, the format is
     * rejected (fail-closed).
     */
    static RDFParser createConfiguredParser(RdfImportFormat format, RdfImportOptions options) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(options, "options");
        if (format == RdfImportFormat.AUTO || format.rdf4jFormat() == null) {
            throw new OntologyImportException("AUTO is not a concrete RDF parser format");
        }

        RDFParser parser;
        try {
            parser = Rio.createParser(format.rdf4jFormat());
        } catch (RuntimeException e) {
            throw new OntologyImportException(
                "RDF4J parser for " + format + " is not available: " + e.getMessage(), e
            );
        }

        switch (format) {
            case RDFXML -> configureXmlSecurity(parser);
            case JSONLD -> configureJsonLdSecurity(parser, options);
            case TURTLE, AUTO -> {
                // Turtle has no DTD or remote-context mechanism.
            }
        }
        return parser;
    }

    private static void configureXmlSecurity(RDFParser parser) {
        setAndVerify(parser, XMLParserSettings.SECURE_PROCESSING, Boolean.TRUE);
        setAndVerify(parser, XMLParserSettings.DISALLOW_DOCTYPE_DECL, Boolean.TRUE);
        setAndVerify(parser, XMLParserSettings.LOAD_EXTERNAL_DTD, Boolean.FALSE);
        setAndVerify(parser, XMLParserSettings.EXTERNAL_GENERAL_ENTITIES, Boolean.FALSE);
        setAndVerify(parser, XMLParserSettings.EXTERNAL_PARAMETER_ENTITIES, Boolean.FALSE);
        setAndVerify(parser, XMLParserSettings.FAIL_ON_SAX_NON_FATAL_ERRORS, Boolean.TRUE);
    }

    private static void configureJsonLdSecurity(RDFParser parser, RdfImportOptions options) {
        setAndVerify(parser, JSONLDSettings.SECURE_MODE, Boolean.TRUE);
        setAndVerify(parser, JSONLDSettings.WHITELIST, Set.of());
        setAndVerify(parser, JSONLDSettings.DOCUMENT_LOADER_CACHE, Boolean.FALSE);
        setAndVerify(parser, JSONLDSettings.EXCEPTION_ON_WARNING, Boolean.TRUE);

        setAndVerify(parser, JSONLDSettings.DOCUMENT_LOADER, new SecureDocumentLoader());
    }

    private static <T> void setAndVerify(RDFParser parser, RioSetting<T> setting, T expected) {
        if (!parser.getSupportedSettings().contains(setting)) {
            throw new OntologyImportException(
                "RDF parser does not support the required security setting "
                    + setting.getKey() + "; the format is rejected"
            );
        }
        try {
            parser.set(setting, expected);
        } catch (RuntimeException e) {
            throw new OntologyImportException(
                "Failed to apply security setting " + setting.getKey() + ": " + e.getMessage(), e
            );
        }
        T actual;
        try {
            actual = parser.getParserConfig().get(setting);
        } catch (RuntimeException e) {
            throw new OntologyImportException(
                "Failed to read back security setting " + setting.getKey() + ": " + e.getMessage(), e
            );
        }
        if (!Objects.equals(expected, actual)) {
            throw new OntologyImportException(
                "Security setting " + setting.getKey() + " was not applied as requested (expected "
                    + expected + ", actual " + actual + "); the format is rejected"
            );
        }
    }

    private static String stripBom(String content) {
        return content.startsWith("\uFEFF") ? content.substring(1) : content;
    }

    private static void enforceMaxBytes(String content, long maxBytes) {
        long bytes = RdfIriSupport.utf8Length(content);
        if (bytes > maxBytes) {
            throw new OntologyImportException(
                "RDF input exceeds the maximum of " + maxBytes + " UTF-8 bytes (" + bytes + ")"
            );
        }
    }

    private static RdfImportFormat detectFormat(String content) {
        String trimmed = content.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return RdfImportFormat.JSONLD;
        }
        if (trimmed.startsWith("<?xml")
            || trimmed.contains("<rdf:RDF")
            || (trimmed.startsWith("<") && trimmed.contains("xmlns"))) {
            return RdfImportFormat.RDFXML;
        }
        return RdfImportFormat.TURTLE;
    }

    /**
     * Rejects remote JSON-LD contexts and any {@code @import} before RDF4J is
     * invoked. Inline context objects remain supported; term mappings do not
     * trigger document loading.
     */
    private static void preflightJsonLd(String content) {
        JsonStructure root;
        try (JsonReader reader = Json.createReader(new StringReader(content))) {
            root = reader.read();
        } catch (JsonException | IllegalStateException e) {
            throw new OntologyImportException("Invalid JSON-LD document: " + e.getMessage(), e);
        }
        scanJson(root);
    }

    private static void scanJson(JsonValue value) {
        switch (value.getValueType()) {
            case OBJECT -> {
                JsonObject object = value.asJsonObject();
                for (Map.Entry<String, JsonValue> entry : object.entrySet()) {
                    String key = entry.getKey();
                    JsonValue child = entry.getValue();
                    if ("@import".equals(key)) {
                        throw new OntologyImportException(
                            "JSON-LD @import is not allowed: the importer is offline-only"
                        );
                    }
                    if ("@context".equals(key)) {
                        checkContext(child);
                    } else {
                        scanJson(child);
                    }
                }
            }
            case ARRAY -> value.asJsonArray().forEach(OntologyRdfImporter::scanJson);
            default -> {
                // scalars carry no context references
            }
        }
    }

    private static void checkContext(JsonValue context) {
        switch (context.getValueType()) {
            case STRING -> checkContextString(((JsonString) context).getString());
            case ARRAY -> {
                JsonArray array = context.asJsonArray();
                for (JsonValue item : array) {
                    if (item.getValueType() == JsonValue.ValueType.STRING) {
                        checkContextString(((JsonString) item).getString());
                    } else {
                        scanJson(item);
                    }
                }
            }
            default -> scanJson(context);
        }
    }

    private static void checkContextString(String value) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        if (RdfIriSupport.isRemoteUrl(trimmed)) {
            throw new OntologyImportException(
                "Remote JSON-LD @context is not allowed (offline import): " + abbreviate(trimmed)
            );
        }
        throw new OntologyImportException(
            "Local or relative JSON-LD @context is not allowed (offline import): " + abbreviate(trimmed)
        );
    }

    private static Optional<OntologyImportException> unwrapImportException(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof OntologyImportException importException) {
                return Optional.of(importException);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static String abbreviate(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "...";
    }

    // ------------------------------------------------------------------
    // Model mapping
    // ------------------------------------------------------------------

    private enum NodeKind {
        CLASS,
        OBJECT_PROPERTY,
        DATATYPE_PROPERTY,
        RELATION_ATTRIBUTE,
        SOURCE_BINDING,
        RELATION_PATTERN
    }

    private enum PropertyKind {
        ENTITY,
        RELATION
    }

    private record PropertyKey(
        PropertyKind kind,
        String ownerIdentity,
        String propertyIdentity
    ) implements Comparable<PropertyKey> {
        @Override
        public int compareTo(PropertyKey other) {
            int result = kind.compareTo(other.kind);
            if (result != 0) {
                return result;
            }
            result = ownerIdentity.compareTo(other.ownerIdentity);
            return result != 0 ? result : propertyIdentity.compareTo(other.propertyIdentity);
        }
    }

    private record PropertyDeclaration(
        PropertyKind kind,
        String ownerIdentity,
        String propertyIdentity,
        String displayName,
        OntologyPropertyType type,
        String description,
        SourceBinding sourceBinding
    ) {
        boolean sameFields(PropertyDeclaration other) {
            return displayName.equals(other.displayName)
                && type == other.type
                && Objects.equals(description, other.description)
                && Objects.equals(sourceBinding, other.sourceBinding);
        }

        List<String> differences(PropertyDeclaration other) {
            List<String> result = new ArrayList<>();
            if (!displayName.equals(other.displayName)) {
                result.add("display name");
            }
            if (type != other.type) {
                result.add("valueType");
            }
            if (!Objects.equals(description, other.description)) {
                result.add("description");
            }
            if (!Objects.equals(sourceBinding, other.sourceBinding)) {
                result.add("sourceBinding");
            }
            return result;
        }
    }

    private record ClassInfo(String iri, String label, String description, SourceBinding binding) {}

    private record RelationInfo(
        String iri,
        String label,
        String description,
        List<OntologyPattern> patterns,
        SourceBinding binding
    ) {}

    private static final class Mapper {

        private final Model model;
        private final int statementCount;
        private final RdfImportOptions options;
        private final SortedSet<String> warnings;
        private final RdfListWalker listWalker;
        private final String extensionNamespace;

        private final Map<String, EnumSet<NodeKind>> kindsByIri = new HashMap<>();
        private final SortedSet<String> classIris = new TreeSet<>();
        private final SortedSet<String> objectPropertyIris = new TreeSet<>();
        private final SortedSet<String> datatypePropertyIris = new TreeSet<>();
        private final SortedSet<String> relationAttributeIris = new TreeSet<>();

        private final Map<String, ClassInfo> classes = new TreeMap<>();
        private final Map<String, String> classLabelByIri = new TreeMap<>();
        private final Map<String, String> classIdentityToIri = new TreeMap<>();
        private final Map<String, RelationInfo> relations = new TreeMap<>();
        private final Map<String, String> relationLabelByIri = new TreeMap<>();
        private final Map<String, String> relationIdentityToIri = new TreeMap<>();
        private final Map<PropertyKey, PropertyDeclaration> declarations = new TreeMap<>();
        private final Map<String, Integer> ignoredPredicates = new TreeMap<>();
        private final Map<String, Integer> ignoredTypes = new TreeMap<>();

        private int sourceBindingCount;

        Mapper(Model model, int statementCount, RdfImportOptions options, SortedSet<String> warnings) {
            this.model = model;
            this.statementCount = statementCount;
            this.options = options;
            this.warnings = warnings;
            this.listWalker = new RdfListWalker(model, options.maxDepth(), options.maxNodes(), warnings);
            this.extensionNamespace = resolveExtensionNamespace();
        }

        ImportResult map() {
            collectNodeKinds();
            buildClasses();
            buildRelations();
            importDatatypeProperties();
            importRelationAttributes();
            collectUnknownAxioms();

            if (statementCount == 0) {
                warnings.add("The RDF document contained no statements.");
            }

            String version = readVersion();
            Map<String, String> metadata = readMetadata();
            List<OntologyEntityType> entityTypes = buildEntityTypes();
            List<OntologyRelationType> relationTypes = buildRelationTypes();

            try {
                Ontology ontology = new Ontology(entityTypes, relationTypes, version, metadata);
                ImportResult.ImportStats stats = new ImportResult.ImportStats(
                    statementCount,
                    entityTypes.size(),
                    relationTypes.size(),
                    declarations.size(),
                    relationTypes.stream().mapToInt(type -> type.patterns().size()).sum(),
                    sourceBindingCount,
                    ignoredPredicates.values().stream().mapToInt(Integer::intValue).sum()
                        + ignoredTypes.values().stream().mapToInt(Integer::intValue).sum()
                );
                return new ImportResult(ontology, List.copyOf(warnings), stats);
            } catch (OntologyImportException e) {
                throw e;
            } catch (IllegalArgumentException e) {
                throw new OntologyImportException("Imported ontology is invalid: " + e.getMessage(), e);
            }
        }

        // --------------------------------------------------------------
        // Extension namespace discovery
        // --------------------------------------------------------------

        private String resolveExtensionNamespace() {
            if (options.extensionNamespace() != null) {
                return options.extensionNamespace();
            }

            Map<String, Integer> scores = new TreeMap<>();
            Set<String> eligible = new HashSet<>();

            for (Statement statement : model) {
                String predicate = statement.getPredicate().stringValue();
                String local = RdfIriSupport.localName(predicate);
                if (RdfVocabulary.EXTENSION_LOCAL_NAMES.contains(local)
                    && !RdfIriSupport.isStandardVocabulary(predicate)) {
                    String namespace = RdfIriSupport.namespaceOf(predicate);
                    scores.merge(namespace, 1, Integer::sum);
                    if (local.equals(RdfVocabulary.OWNER)
                        || local.equals(RdfVocabulary.RELATION_PATTERN)
                        || local.equals(RdfVocabulary.ATTRIBUTE_NAME)
                        || local.equals(RdfVocabulary.VALUE_TYPE)
                        || local.equals(RdfVocabulary.SOURCE_BINDING)
                        || local.equals(RdfVocabulary.DEFINITION_HASH)
                        || local.equals(RdfVocabulary.VERSION)) {
                        eligible.add(namespace);
                    }
                }
                if (statement.getPredicate().equals(RDF.TYPE)
                    && statement.getObject() instanceof IRI typeIri) {
                    String typeLocal = RdfIriSupport.localName(typeIri.stringValue());
                    if (typeLocal.equals(RdfVocabulary.RELATION_ATTRIBUTE_TYPE)
                        || typeLocal.equals(RdfVocabulary.SOURCE_BINDING_TYPE)
                        || typeLocal.equals(RdfVocabulary.RELATION_PATTERN_TYPE)) {
                        String namespace = RdfIriSupport.namespaceOf(typeIri.stringValue());
                        if (!RdfIriSupport.isStandardVocabulary(typeIri.stringValue())) {
                            scores.merge(namespace, 3, Integer::sum);
                            eligible.add(namespace);
                        }
                    }
                }
            }

            if (eligible.isEmpty()) {
                return null;
            }

            String best = null;
            int bestScore = Integer.MIN_VALUE;
            for (String namespace : eligible) {
                int score = scores.getOrDefault(namespace, 0);
                if (score > bestScore || (score == bestScore && best != null && namespace.compareTo(best) < 0)) {
                    best = namespace;
                    bestScore = score;
                }
            }
            if (eligible.size() > 1) {
                warnings.add(
                    "Multiple LightRAG extension namespaces detected; using '" + best + "'"
                );
            }
            return best;
        }

        private IRI extensionIri(String localName) {
            if (extensionNamespace == null) {
                throw new IllegalStateException("No extension namespace resolved");
            }
            return VALUE_FACTORY.createIRI(extensionNamespace + localName);
        }

        // --------------------------------------------------------------
        // Node classification
        // --------------------------------------------------------------

        private void collectNodeKinds() {
            for (Statement statement : model.filter(null, RDF.TYPE, null)) {
                Resource subject = statement.getSubject();
                Value typeValue = statement.getObject();
                if (!(typeValue instanceof IRI typeIri)) {
                    continue;
                }
                String type = typeIri.stringValue();

                if (type.equals(OWL.CLASS.stringValue()) || type.equals(RDFS.CLASS.stringValue())) {
                    registerClass(subject);
                } else if (type.equals(OWL.OBJECTPROPERTY.stringValue())) {
                    registerObjectProperty(subject);
                } else if (type.equals(OWL.DATATYPEPROPERTY.stringValue())) {
                    registerDatatypeProperty(subject);
                } else if (type.equals(OWL.ONTOLOGY.stringValue())) {
                    // Ontology header is metadata only; nothing to import from the header itself.
                } else if (extensionNamespace != null
                    && type.equals(extensionIri(RdfVocabulary.RELATION_ATTRIBUTE_TYPE).stringValue())) {
                    registerRelationAttribute(subject);
                } else if (extensionNamespace != null
                    && type.equals(extensionIri(RdfVocabulary.SOURCE_BINDING_TYPE).stringValue())) {
                    // Binding nodes are normally blank nodes referenced from lg:sourceBinding.
                    if (subject instanceof IRI) {
                        registerKind(subject, NodeKind.SOURCE_BINDING, "source binding");
                    }
                } else if (extensionNamespace != null
                    && type.equals(extensionIri(RdfVocabulary.RELATION_PATTERN_TYPE).stringValue())) {
                    // Pattern nodes are normally blank nodes referenced from lg:relationPattern.
                    if (subject instanceof IRI) {
                        registerKind(subject, NodeKind.RELATION_PATTERN, "relation pattern");
                    }
                }
                // Remaining types are counted as ignored axioms by collectUnknownAxioms().
            }
        }

        private void registerClass(Resource subject) {
            if (!(subject instanceof IRI iri)) {
                warnings.add("Anonymous owl:Class/rdfs:Class declarations are not imported.");
                return;
            }
            String value = iri.stringValue();
            if (RdfIriSupport.isStandardVocabulary(value)) {
                return;
            }
            registerKind(iri, NodeKind.CLASS, "class");
            classIris.add(value);
        }

        private void registerObjectProperty(Resource subject) {
            if (!(subject instanceof IRI iri)) {
                warnings.add("Anonymous owl:ObjectProperty declarations are not imported.");
                return;
            }
            String value = iri.stringValue();
            if (RdfIriSupport.isStandardVocabulary(value)) {
                return;
            }
            registerKind(iri, NodeKind.OBJECT_PROPERTY, "object property");
            objectPropertyIris.add(value);
        }

        private void registerDatatypeProperty(Resource subject) {
            if (!(subject instanceof IRI iri)) {
                warnings.add("Anonymous owl:DatatypeProperty declarations are not imported.");
                return;
            }
            String value = iri.stringValue();
            if (RdfIriSupport.isStandardVocabulary(value)) {
                return;
            }
            registerKind(iri, NodeKind.DATATYPE_PROPERTY, "datatype property");
            datatypePropertyIris.add(value);
        }

        private void registerRelationAttribute(Resource subject) {
            if (!(subject instanceof IRI iri)) {
                warnings.add("Anonymous lg:RelationAttribute declarations are not imported.");
                return;
            }
            String value = iri.stringValue();
            if (RdfIriSupport.isStandardVocabulary(value)) {
                return;
            }
            registerKind(iri, NodeKind.RELATION_ATTRIBUTE, "relation attribute");
            relationAttributeIris.add(value);
        }

        private void registerKind(Resource subject, NodeKind kind, String label) {
            if (!(subject instanceof IRI iri)) {
                warnings.add("Anonymous " + label + " declarations are not imported.");
                return;
            }
            String value = iri.stringValue();
            EnumSet<NodeKind> kinds = kindsByIri.computeIfAbsent(value, key -> EnumSet.noneOf(NodeKind.class));
            if (!kinds.isEmpty() && !kinds.contains(kind)) {
                throw new OntologyImportException(
                    "IRI '" + value + "' is declared as both " + kinds + " and " + kind
                );
            }
            kinds.add(kind);
        }

        // --------------------------------------------------------------
        // Classes and relations
        // --------------------------------------------------------------

        private void buildClasses() {
            for (String iriString : classIris) {
                IRI subject = VALUE_FACTORY.createIRI(iriString);
                String label = labelOrLocalName(subject, iriString, "class");
                String description = singleLiteral(
                    subject, RDFS.COMMENT, "rdfs:comment of " + iriString);
                SourceBinding binding = sourceBinding(subject);
                String identity = identityOf(label, "class");

                String previous = classIdentityToIri.putIfAbsent(identity, iriString);
                if (previous != null) {
                    throw new OntologyImportException(
                        "Duplicate entity identity '" + identity + "' for class IRIs "
                            + previous + " and " + iriString
                    );
                }
                classes.put(iriString, new ClassInfo(iriString, label, description, binding));
                classLabelByIri.put(iriString, label);
            }
        }

        private void buildRelations() {
            for (String iriString : objectPropertyIris) {
                IRI subject = VALUE_FACTORY.createIRI(iriString);
                String label = labelOrLocalName(subject, iriString, "object property");
                String description = singleLiteral(
                    subject, RDFS.COMMENT, "rdfs:comment of " + iriString);
                SourceBinding binding = sourceBinding(subject);
                List<OntologyPattern> patterns = readPatterns(subject, iriString);
                String identity = identityOf(label, "relation");

                String previous = relationIdentityToIri.putIfAbsent(identity, iriString);
                if (previous != null) {
                    throw new OntologyImportException(
                        "Duplicate relation identity '" + identity + "' for object property IRIs "
                            + previous + " and " + iriString
                    );
                }
                relations.put(iriString, new RelationInfo(
                    iriString, label, description, patterns, binding
                ));
                relationLabelByIri.put(iriString, label);
            }
        }

        private List<OntologyPattern> readPatterns(Resource subject, String iriString) {
            Map<String, OntologyPattern> result = new TreeMap<>();

            if (extensionNamespace != null) {
                List<Value> patternNodes = objects(subject, extensionIri(RdfVocabulary.RELATION_PATTERN));
                if (!patternNodes.isEmpty()) {
                    if (!objects(subject, RDFS.DOMAIN).isEmpty()
                        || !objects(subject, RDFS.RANGE).isEmpty()) {
                        warnings.add(
                            "Relation '" + iriString + "' declares both lg:relationPattern and "
                                + "rdfs:domain/rdfs:range; the latter are ignored."
                        );
                    }
                    for (Value node : patternNodes) {
                        if (!(node instanceof Resource resource)) {
                            warnings.add("lg:relationPattern of " + iriString + " is not a resource; skipped.");
                            continue;
                        }
                        Value source = patternEndpoint(
                            resource,
                            extensionIri(RdfVocabulary.SOURCE_CLASS),
                            RDFS.DOMAIN,
                            "lg:sourceClass of " + iriString,
                            "rdfs:domain of " + iriString
                        );
                        Value target = patternEndpoint(
                            resource,
                            extensionIri(RdfVocabulary.TARGET_CLASS),
                            RDFS.RANGE,
                            "lg:targetClass of " + iriString,
                            "rdfs:range of " + iriString
                        );
                        addPattern(result, source, target, "lg:relationPattern of " + iriString);
                    }
                    return List.copyOf(result.values());
                }
            }

            List<Value> domains = objects(subject, RDFS.DOMAIN);
            List<Value> ranges = objects(subject, RDFS.RANGE);
            if (domains.isEmpty() && ranges.isEmpty()) {
                return List.of();
            }
            if (domains.size() != 1 || ranges.size() != 1) {
                warnings.add(
                    "Relation '" + iriString
                        + "' has multiple or incomplete rdfs:domain/rdfs:range declarations; "
                        + "patterns are omitted."
                );
                return List.of();
            }
            addPattern(result, domains.get(0), ranges.get(0), "rdfs:domain/rdfs:range of " + iriString);
            return List.copyOf(result.values());
        }

        /**
         * Resolves one pattern endpoint with an explicit priority: the
         * extension predicate wins when present, and the fallback predicate
         * is used only when the extension predicate is absent. Each
         * predicate must still be internally unique; priority between
         * fields never hides a conflict within one field.
         */
        private Value patternEndpoint(
            Resource resource,
            IRI extensionPredicate,
            IRI fallbackPredicate,
            String extensionField,
            String fallbackField
        ) {
            List<Value> extensionValues = objects(resource, extensionPredicate);
            IRI extension = uniqueIriEndpoint(extensionValues, extensionField);
            IRI fallback = uniqueIriEndpoint(objects(resource, fallbackPredicate), fallbackField);
            return extensionValues.isEmpty() ? fallback : extension;
        }

        private static IRI uniqueIriEndpoint(List<Value> values, String field) {
            Map<String, IRI> iris = new TreeMap<>();
            boolean anonymous = false;
            for (Value value : values) {
                if (value instanceof IRI iri) {
                    iris.putIfAbsent(iri.stringValue(), iri);
                } else {
                    anonymous = true;
                }
            }
            if (iris.size() > 1) {
                throw new OntologyImportException(
                    field + " has conflicting values: " + iris.keySet()
                );
            }
            if (!iris.isEmpty() && anonymous) {
                throw new OntologyImportException(
                    field + " mixes IRI and anonymous endpoint values"
                );
            }
            return iris.isEmpty() ? null : iris.values().iterator().next();
        }

        private void addPattern(
            Map<String, OntologyPattern> result,
            Value sourceValue,
            Value targetValue,
            String context
        ) {
            if (!(sourceValue instanceof IRI source) || !(targetValue instanceof IRI target)) {
                warnings.add("Anonymous or missing endpoint class in " + context + "; pattern skipped.");
                return;
            }
            String sourceLabel = classLabelByIri.get(source.stringValue());
            String targetLabel = classLabelByIri.get(target.stringValue());
            if (sourceLabel == null || targetLabel == null) {
                warnings.add(
                    context + " references an undeclared class ("
                        + source.stringValue() + " -> " + target.stringValue()
                        + "); pattern skipped."
                );
                return;
            }
            OntologyPattern pattern = new OntologyPattern(sourceLabel, targetLabel);
            result.putIfAbsent(pattern.identityId(), pattern);
        }

        // --------------------------------------------------------------
        // Datatype properties
        // --------------------------------------------------------------

        private void importDatatypeProperties() {
            for (String iriString : datatypePropertyIris) {
                IRI subject = VALUE_FACTORY.createIRI(iriString);
                List<Value> ownerValues = extensionNamespace == null
                    ? List.of()
                    : objects(subject, extensionIri(RdfVocabulary.OWNER));
                List<IRI> explicitOwners = distinctIris(ownerValues, "lg:owner of " + iriString);
                if (explicitOwners.size() > 1) {
                    throw new OntologyImportException(
                        "Datatype property '" + iriString + "' has multiple lg:owner values"
                    );
                }

                List<Value> domains = objects(subject, RDFS.DOMAIN);
                List<IRI> owners;
                if (explicitOwners.size() == 1) {
                    IRI owner = explicitOwners.get(0);
                    String ownerIri = owner.stringValue();
                    if (!classLabelByIri.containsKey(ownerIri)) {
                        throw new OntologyImportException(
                            "lg:owner '" + ownerIri + "' of datatype property '" + iriString
                                + "' is not a declared owl:Class"
                        );
                    }
                    for (Value domain : domains) {
                        if (isUnionExpression(domain)) {
                            warnings.add(
                                "Datatype property '" + iriString
                                    + "' declares both lg:owner and a union rdfs:domain; "
                                    + "lg:owner takes precedence and the union is ignored."
                            );
                            continue;
                        }
                        if (domain instanceof IRI domainIri
                            && domainIri.stringValue().equals(ownerIri)) {
                            continue;
                        }
                        throw new OntologyImportException(
                            "Datatype property '" + iriString + "' declares lg:owner '" + ownerIri
                                + "' but rdfs:domain '" + describe(domain) + "'"
                        );
                    }
                    owners = List.of(owner);
                } else {
                    if (domains.isEmpty()) {
                        warnings.add(
                            "Datatype property '" + iriString
                                + "' has neither lg:owner nor rdfs:domain; skipped."
                        );
                        continue;
                    }
                    if (domains.size() > 1) {
                        throw new OntologyImportException(
                            "Datatype property '" + iriString
                                + "' has multiple rdfs:domain values; ownership is ambiguous"
                        );
                    }
                    owners = resolveOwners(domains.get(0), iriString);
                    if (owners.isEmpty()) {
                        continue;
                    }
                }

                String name = labelOrLocalName(subject, iriString, "entity property");
                OntologyPropertyType type = resolvePropertyType(subject, iriString);
                String description = singleLiteral(
                    subject, RDFS.COMMENT, "rdfs:comment of " + iriString);
                SourceBinding binding = sourceBinding(subject);

                for (IRI owner : owners) {
                    String ownerLabel = classLabelByIri.get(owner.stringValue());
                    addDeclaration(PropertyKind.ENTITY, ownerLabel, name, type, description, binding);
                }
            }
        }

        private List<IRI> resolveOwners(Value domain, String propertyIri) {
            if (domain instanceof IRI iri) {
                if (isUnionExpression(iri)) {
                    return unionMembers(iri, "rdfs:domain of " + propertyIri);
                }
                if (classLabelByIri.containsKey(iri.stringValue())) {
                    return List.of(iri);
                }
                warnings.add(
                    "Datatype property '" + propertyIri + "' has rdfs:domain '"
                        + iri.stringValue() + "' which is not a declared owl:Class; skipped."
                );
                return List.of();
            }
            if (domain instanceof BNode) {
                if (isUnionExpression(domain)) {
                    return unionMembers(domain, "rdfs:domain of " + propertyIri);
                }
                warnings.add(
                    "Datatype property '" + propertyIri
                        + "' has an anonymous rdfs:domain class expression; skipped."
                );
                return List.of();
            }
            warnings.add(
                "Datatype property '" + propertyIri
                    + "' has a non-resource rdfs:domain; skipped."
            );
            return List.of();
        }

        private List<IRI> unionMembers(Value unionHead, String context) {
            if (!(unionHead instanceof Resource head)) {
                warnings.add(context + " has an invalid owl:unionOf head; skipped.");
                return List.of();
            }
            List<Value> unionLists = objects(head, OWL.UNIONOF);
            if (unionLists.size() != 1) {
                warnings.add(
                    context + " must contain exactly one owl:unionOf, found " + unionLists.size() + "; skipped."
                );
                return List.of();
            }
            RdfListWalker.ReadResult read = listWalker.read(unionLists.get(0), context);
            if (!read.complete()) {
                warnings.add(context + " is incomplete or cyclic; the union is ignored.");
                return List.of();
            }
            if (read.values().isEmpty()) {
                warnings.add(context + " contains an empty owl:unionOf; skipped.");
                return List.of();
            }

            Set<String> seen = new LinkedHashSet<>();
            List<IRI> members = new ArrayList<>();
            for (Value member : read.values()) {
                if (!(member instanceof IRI iri) || isUnionExpression(member)) {
                    warnings.add(
                        context + " contains an anonymous or nested class expression; the union is skipped."
                    );
                    return List.of();
                }
                String value = iri.stringValue();
                if (!classLabelByIri.containsKey(value)) {
                    warnings.add(
                        context + " references an undeclared class '" + value + "'; the union is skipped."
                    );
                    return List.of();
                }
                if (seen.add(value)) {
                    members.add(iri);
                }
            }
            return List.copyOf(members);
        }

        private boolean isUnionExpression(Value value) {
            return value instanceof Resource resource
                && !objects(resource, OWL.UNIONOF).isEmpty();
        }

        // --------------------------------------------------------------
        // Relation attributes
        // --------------------------------------------------------------

        private void importRelationAttributes() {
            for (String iriString : relationAttributeIris) {
                IRI subject = VALUE_FACTORY.createIRI(iriString);
                List<Value> ownerValues = extensionNamespace == null
                    ? List.of()
                    : objects(subject, extensionIri(RdfVocabulary.OWNER));
                List<IRI> owners = distinctIris(ownerValues, "lg:owner of " + iriString);
                if (owners.size() != 1) {
                    throw new OntologyImportException(
                        "Relation attribute '" + iriString + "' requires exactly one lg:owner"
                    );
                }
                String ownerIri = owners.get(0).stringValue();
                String ownerLabel = relationLabelByIri.get(ownerIri);
                if (ownerLabel == null) {
                    throw new OntologyImportException(
                        "lg:owner '" + ownerIri + "' of relation attribute '" + iriString
                            + "' is not a declared owl:ObjectProperty"
                    );
                }

                String name = relationAttributeName(subject, iriString);
                OntologyPropertyType type = resolvePropertyType(subject, iriString);
                String description = extensionNamespace == null
                    ? null
                    : singleLiteral(
                        subject,
                        extensionIri(RdfVocabulary.DESCRIPTION),
                        "lg:description of " + iriString
                    );
                // The comment is validated even when lg:description wins, so
                // conflicting comments cannot hide behind the preferred field.
                String comment = singleLiteral(
                    subject, RDFS.COMMENT, "rdfs:comment of " + iriString);
                if (description == null) {
                    description = comment;
                }
                SourceBinding binding = sourceBinding(subject);
                addDeclaration(PropertyKind.RELATION, ownerLabel, name, type, description, binding);
            }
        }

        private String relationAttributeName(Resource subject, String iriString) {
            // The label is validated even when lg:attributeName wins, so
            // conflicting labels cannot hide behind the preferred field.
            String label = singleLiteral(
                subject, RDFS.LABEL, "rdfs:label of " + iriString);
            if (extensionNamespace != null) {
                String attributeName = singleLiteral(
                    subject,
                    extensionIri(RdfVocabulary.ATTRIBUTE_NAME),
                    "lg:attributeName of " + iriString
                );
                if (attributeName != null) {
                    return attributeName;
                }
            }
            if (label != null) {
                return label;
            }
            String decoded = RdfIriSupport.percentDecode(RdfIriSupport.localName(iriString));
            if (decoded.isBlank()) {
                throw new OntologyImportException(
                    "Cannot derive a name for relation attribute '" + iriString + "'"
                );
            }
            return decoded.strip();
        }

        // --------------------------------------------------------------
        // Shared property helpers
        // --------------------------------------------------------------

        private OntologyPropertyType resolvePropertyType(Resource subject, String iriString) {
            List<Value> ranges = objects(subject, RDFS.RANGE);
            Set<String> rangeIris = new TreeSet<>();
            boolean nonIriRange = false;
            for (Value value : ranges) {
                if (value instanceof IRI iri) {
                    rangeIris.add(iri.stringValue());
                } else {
                    nonIriRange = true;
                }
            }
            if (rangeIris.size() > 1) {
                throw new OntologyImportException(
                    "Property '" + iriString + "' has conflicting rdfs:range values: " + rangeIris
                );
            }

            // The range is validated even when lg:valueType wins, so conflicting
            // range declarations cannot hide behind the preferred field.
            OntologyPropertyType extensionType = extensionPropertyType(subject, iriString);
            if (extensionType != null) {
                return extensionType;
            }
            if (nonIriRange) {
                warnings.add("Property '" + iriString + "' has a non-IRI rdfs:range; using STRING.");
            }
            if (rangeIris.isEmpty()) {
                return OntologyPropertyType.STRING;
            }
            String range = rangeIris.iterator().next();
            Optional<OntologyPropertyType> mapped = RdfFormatValue.fromXsdDatatype(range);
            if (mapped.isEmpty()) {
                warnings.add(
                    "Property '" + iriString + "' has unsupported rdfs:range '" + range
                        + "'; using STRING."
                );
                return OntologyPropertyType.STRING;
            }
            return mapped.get();
        }

        private OntologyPropertyType extensionPropertyType(Resource subject, String iriString) {
            if (extensionNamespace == null) {
                return null;
            }
            List<Value> valueTypeValues = objects(subject, extensionIri(RdfVocabulary.VALUE_TYPE));
            if (valueTypeValues.isEmpty()) {
                return null;
            }
            Set<String> lexicalValues = literalValues(valueTypeValues, "lg:valueType of " + iriString);
            if (lexicalValues.size() > 1) {
                throw new OntologyImportException(
                    "Property '" + iriString + "' has conflicting lg:valueType values: " + lexicalValues
                );
            }
            String lexical = lexicalValues.iterator().next();
            if (lexical.isBlank()) {
                throw new OntologyImportException(
                    "Property '" + iriString + "' has a blank lg:valueType"
                );
            }
            Optional<OntologyPropertyType> parsed;
            try {
                parsed = RdfFormatValue.fromValueType(lexical);
            } catch (IllegalArgumentException e) {
                throw new OntologyImportException(
                    "Property '" + iriString + "' has an unsupported lg:valueType '"
                        + lexical + "'", e
                );
            }
            if (parsed.isEmpty()) {
                throw new OntologyImportException(
                    "Property '" + iriString + "' has an unsupported lg:valueType '"
                        + lexical + "'"
                );
            }
            return parsed.get();
        }

        private void addDeclaration(
            PropertyKind kind,
            String ownerLabel,
            String propertyName,
            OntologyPropertyType type,
            String description,
            SourceBinding sourceBinding
        ) {
            String ownerIdentity = identityOf(ownerLabel, kind + " owner");
            String propertyIdentity = identityOf(propertyName, kind + " property");
            PropertyKey key = new PropertyKey(kind, ownerIdentity, propertyIdentity);
            PropertyDeclaration declaration = new PropertyDeclaration(
                kind,
                ownerIdentity,
                propertyIdentity,
                propertyName.strip(),
                type,
                normalize(description),
                sourceBinding
            );

            PropertyDeclaration previous = declarations.putIfAbsent(key, declaration);
            if (previous != null && !previous.sameFields(declaration)) {
                throw new OntologyImportException(
                    "Conflicting " + kind.name().toLowerCase(Locale.ROOT) + " property declaration for owner '"
                        + ownerIdentity + "' and property '" + propertyIdentity + "': "
                        + String.join(", ", previous.differences(declaration)) + " differ"
                );
            }
        }

        private SourceBinding sourceBinding(Resource subject) {
            if (!options.includeSourceBindings() || extensionNamespace == null) {
                return null;
            }
            List<Value> nodes = objects(subject, extensionIri(RdfVocabulary.SOURCE_BINDING));
            if (nodes.isEmpty()) {
                return null;
            }
            Set<SourceBinding> bindings = new LinkedHashSet<>();
            for (Value node : nodes) {
                if (!(node instanceof Resource resource)) {
                    throw new OntologyImportException(
                        "lg:sourceBinding of '" + describe(subject) + "' is not a resource"
                    );
                }
                bindings.add(parseSourceBinding(resource));
            }
            if (bindings.size() > 1) {
                throw new OntologyImportException(
                    "Subject '" + describe(subject) + "' has conflicting lg:SourceBinding values"
                );
            }
            SourceBinding binding = bindings.iterator().next();
            sourceBindingCount++;
            return binding;
        }

        private SourceBinding parseSourceBinding(Resource node) {
            String tableName = singleLiteral(node, extensionIri(RdfVocabulary.TABLE_NAME));
            String column = singleLiteral(node, extensionIri(RdfVocabulary.COLUMN));
            String refTableName = singleLiteral(node, extensionIri(RdfVocabulary.REF_TABLE_NAME));
            String refColumn = singleLiteral(node, extensionIri(RdfVocabulary.REF_COLUMN));
            Boolean nullable = nullable(node);
            try {
                return new SourceBinding(tableName, column, refTableName, refColumn, nullable);
            } catch (IllegalArgumentException e) {
                throw new OntologyImportException(
                    "Invalid lg:SourceBinding at '" + describe(node) + "': " + e.getMessage(), e
                );
            }
        }

        private Boolean nullable(Resource node) {
            List<Value> values = objects(node, extensionIri(RdfVocabulary.NULLABLE));
            if (values.isEmpty()) {
                return null;
            }
            Set<String> lexicalValues = literalValues(values, "lg:nullable of " + describe(node));
            if (lexicalValues.size() > 1) {
                throw new OntologyImportException(
                    "lg:SourceBinding '" + describe(node) + "' has conflicting lg:nullable values"
                );
            }
            String lexical = lexicalValues.iterator().next().strip().toLowerCase(Locale.ROOT);
            return switch (lexical) {
                case "true", "1" -> Boolean.TRUE;
                case "false", "0" -> Boolean.FALSE;
                default -> {
                    warnings.add(
                        "lg:SourceBinding '" + describe(node)
                            + "' has an unsupported lg:nullable value '" + lexical + "'; ignored."
                    );
                    yield null;
                }
            };
        }

        private String singleLiteral(Resource subject, IRI predicate) {
            List<Value> values = objects(subject, predicate);
            if (values.isEmpty()) {
                return null;
            }
            Set<String> lexicalValues = literalValues(values, predicate.stringValue());
            if (lexicalValues.size() > 1) {
                throw new OntologyImportException(
                    "Subject '" + describe(subject) + "' has multiple values for " + predicate.stringValue()
                );
            }
            return lexicalValues.iterator().next();
        }

        // --------------------------------------------------------------
        // Version, metadata and unknown axioms
        // --------------------------------------------------------------

        private String readVersion() {
            Set<String> versions = new TreeSet<>();
            for (Statement statement : model.filter(null, OWL.VERSIONINFO, null)) {
                if (statement.getObject() instanceof Literal literal) {
                    String label = literal.getLabel().strip();
                    if (!label.isEmpty()) {
                        versions.add(label);
                    }
                }
            }
            if (extensionNamespace != null) {
                for (Statement statement : model.filter(null, extensionIri(RdfVocabulary.VERSION), null)) {
                    if (statement.getObject() instanceof Literal literal) {
                        String label = literal.getLabel().strip();
                        if (!label.isEmpty()) {
                            versions.add(label);
                        }
                    }
                }
            }
            if (versions.size() > 1) {
                throw new OntologyImportException(
                    "Conflicting ontology versions: " + versions
                );
            }
            return versions.isEmpty() ? null : versions.iterator().next();
        }

        private Map<String, String> readMetadata() {
            if (extensionNamespace == null) {
                return Map.of();
            }
            Set<String> hashes = new TreeSet<>();
            for (Statement statement : model.filter(null, extensionIri(RdfVocabulary.DEFINITION_HASH), null)) {
                if (statement.getObject() instanceof Literal literal) {
                    String label = literal.getLabel().strip();
                    if (!label.isEmpty()) {
                        hashes.add(label);
                    }
                }
            }
            if (hashes.size() > 1) {
                throw new OntologyImportException(
                    "Conflicting lg:definitionHash values: " + hashes
                );
            }
            return hashes.isEmpty()
                ? Map.of()
                : Map.of(RdfVocabulary.DEFINITION_HASH, hashes.iterator().next());
        }

        private void collectUnknownAxioms() {
            Set<String> recognizedPredicates = new HashSet<>(Set.of(
                RDF.TYPE.stringValue(),
                RDFS.LABEL.stringValue(),
                RDFS.COMMENT.stringValue(),
                RDFS.DOMAIN.stringValue(),
                RDFS.RANGE.stringValue(),
                OWL.UNIONOF.stringValue(),
                OWL.VERSIONINFO.stringValue(),
                RDF.FIRST.stringValue(),
                RDF.REST.stringValue()
            ));
            Set<String> recognizedTypes = new HashSet<>(Set.of(
                OWL.CLASS.stringValue(),
                RDFS.CLASS.stringValue(),
                OWL.OBJECTPROPERTY.stringValue(),
                OWL.DATATYPEPROPERTY.stringValue(),
                OWL.ONTOLOGY.stringValue()
            ));
            if (extensionNamespace != null) {
                for (String local : List.of(
                    RdfVocabulary.OWNER,
                    RdfVocabulary.VALUE_TYPE,
                    RdfVocabulary.RELATION_PATTERN,
                    RdfVocabulary.RELATION,
                    RdfVocabulary.SOURCE_CLASS,
                    RdfVocabulary.TARGET_CLASS,
                    RdfVocabulary.ATTRIBUTE_NAME,
                    RdfVocabulary.DESCRIPTION,
                    RdfVocabulary.SOURCE_BINDING,
                    RdfVocabulary.TABLE_NAME,
                    RdfVocabulary.COLUMN,
                    RdfVocabulary.REF_TABLE_NAME,
                    RdfVocabulary.REF_COLUMN,
                    RdfVocabulary.NULLABLE,
                    RdfVocabulary.DEFINITION_HASH,
                    RdfVocabulary.VERSION
                )) {
                    recognizedPredicates.add(extensionIri(local).stringValue());
                }
                recognizedTypes.add(extensionIri(RdfVocabulary.RELATION_ATTRIBUTE_TYPE).stringValue());
                recognizedTypes.add(extensionIri(RdfVocabulary.SOURCE_BINDING_TYPE).stringValue());
                recognizedTypes.add(extensionIri(RdfVocabulary.RELATION_PATTERN_TYPE).stringValue());
            }

            for (Statement statement : model) {
                String predicate = statement.getPredicate().stringValue();
                if (!recognizedPredicates.contains(predicate)) {
                    ignoredPredicates.merge(predicate, 1, Integer::sum);
                } else if (statement.getPredicate().equals(RDF.TYPE)) {
                    if (statement.getObject() instanceof IRI type) {
                        if (!recognizedTypes.contains(type.stringValue())) {
                            recordIgnoredType(type.stringValue());
                        }
                    } else {
                        recordIgnoredType("(anonymous)");
                    }
                }
            }

            if (!options.allowUnknownAxioms()) {
                return;
            }
            for (Map.Entry<String, Integer> entry : ignoredPredicates.entrySet()) {
                String suffix = entry.getKey().equals(OWL.IMPORTS.stringValue())
                    ? "; owl:imports is never followed"
                    : "";
                warnings.add(
                    "Ignored unsupported RDF predicate " + entry.getKey()
                        + " (" + entry.getValue() + " statement(s)" + suffix + ")."
                );
            }
            for (Map.Entry<String, Integer> entry : ignoredTypes.entrySet()) {
                warnings.add(
                    "Ignored unsupported rdf:type " + entry.getKey()
                        + " (" + entry.getValue() + " statement(s))."
                );
            }
        }

        private void recordIgnoredType(String type) {
            ignoredTypes.merge(type, 1, Integer::sum);
        }

        // --------------------------------------------------------------
        // Assembly
        // --------------------------------------------------------------

        private List<OntologyEntityType> buildEntityTypes() {
            List<OntologyEntityType> result = new ArrayList<>(classes.size());
            for (ClassInfo info : classes.values()) {
                String identity = identityOf(info.label(), "class");
                result.add(new OntologyEntityType(
                    info.label(),
                    info.description(),
                    propertiesFor(PropertyKind.ENTITY, identity),
                    info.binding()
                ));
            }
            result.sort(Comparator.comparing(OntologyEntityType::identityId));
            return List.copyOf(result);
        }

        private List<OntologyRelationType> buildRelationTypes() {
            List<OntologyRelationType> result = new ArrayList<>(relations.size());
            for (RelationInfo info : relations.values()) {
                String identity = identityOf(info.label(), "relation");
                List<OntologyPattern> patterns = info.patterns().stream()
                    .sorted(Comparator.comparing(OntologyPattern::identityId))
                    .toList();
                result.add(new OntologyRelationType(
                    info.label(),
                    info.description(),
                    patterns,
                    propertiesFor(PropertyKind.RELATION, identity),
                    info.binding()
                ));
            }
            result.sort(Comparator.comparing(OntologyRelationType::identityId));
            return List.copyOf(result);
        }

        private List<OntologyProperty> propertiesFor(PropertyKind kind, String ownerIdentity) {
            List<OntologyProperty> result = new ArrayList<>();
            for (PropertyDeclaration declaration : declarations.values()) {
                if (declaration.kind() != kind
                    || !declaration.ownerIdentity().equals(ownerIdentity)) {
                    continue;
                }
                result.add(new OntologyProperty(
                    declaration.displayName(),
                    declaration.type(),
                    declaration.description(),
                    declaration.sourceBinding()
                ));
            }
            result.sort(Comparator.comparing(OntologyProperty::identityId));
            return List.copyOf(result);
        }

        // --------------------------------------------------------------
        // Model helpers
        // --------------------------------------------------------------

        private List<Value> objects(Resource subject, IRI predicate) {
            List<Value> values = new ArrayList<>();
            for (Statement statement : model.filter(subject, predicate, null)) {
                values.add(statement.getObject());
            }
            values.sort(Comparator.comparing(Mapper::sortKey));
            return values;
        }

        private String labelOrLocalName(Resource subject, String iriString, String kind) {
            String label = singleLiteral(subject, RDFS.LABEL, "rdfs:label of " + iriString);
            if (label != null) {
                return label;
            }
            String decoded = RdfIriSupport.percentDecode(RdfIriSupport.localName(iriString));
            if (decoded.isBlank()) {
                throw new OntologyImportException(
                    "Cannot derive a " + kind + " label from IRI '" + iriString + "'"
                );
            }
            return decoded.strip();
        }

        /**
         * Returns the unique non-blank literal for a scalar field. Distinct
         * values for the same subject and predicate are a hard conflict;
         * duplicate spellings are deduplicated after trimming.
         */
        private String singleLiteral(Resource subject, IRI predicate, String field) {
            Set<String> values = new TreeSet<>();
            for (Value value : objects(subject, predicate)) {
                if (!(value instanceof Literal literal)) {
                    warnings.add(field + " has a non-literal value; ignored.");
                    continue;
                }
                String lexical = literal.getLabel().strip();
                if (!lexical.isEmpty()) {
                    values.add(lexical);
                }
            }
            if (values.size() > 1) {
                throw new OntologyImportException(
                    field + " has conflicting values: " + values
                );
            }
            return values.isEmpty() ? null : values.iterator().next();
        }

        private Set<String> literalValues(List<Value> values, String field) {
            Set<String> result = new TreeSet<>();
            for (Value value : values) {
                if (!(value instanceof Literal literal)) {
                    throw new OntologyImportException(
                        field + " must be a literal, found " + describe(value)
                    );
                }
                result.add(literal.getLabel().strip());
            }
            return result;
        }

        private List<IRI> distinctIris(List<Value> values, String field) {
            Map<String, IRI> result = new TreeMap<>();
            for (Value value : values) {
                if (!(value instanceof IRI iri)) {
                    throw new OntologyImportException(
                        field + " must be an IRI, found " + describe(value)
                    );
                }
                result.putIfAbsent(iri.stringValue(), iri);
            }
            return List.copyOf(result.values());
        }

        private static String identityOf(String label, String context) {
            if (label == null || label.isBlank()) {
                throw new OntologyImportException("Blank " + context + " label");
            }
            try {
                return Identity.identityId(label);
            } catch (IllegalArgumentException e) {
                throw new OntologyImportException(
                    "Invalid " + context + " label '" + label + "': " + e.getMessage(), e
                );
            }
        }

        private static String normalize(String value) {
            if (value == null) {
                return null;
            }
            String stripped = value.strip();
            return stripped.isEmpty() ? null : stripped;
        }

        private static String sortKey(Value value) {
            if (value instanceof BNode bNode) {
                return "_:" + bNode.getID();
            }
            if (value instanceof Triple triple) {
                return "<<" + sortKey(triple.getSubject()) + " "
                    + triple.getPredicate().stringValue() + " "
                    + sortKey(triple.getObject()) + ">>";
            }
            return value.stringValue();
        }

        private static String describe(Value value) {
            if (value == null) {
                return "null";
            }
            String text = sortKey(value);
            return text.length() <= 120 ? text : text.substring(0, 120) + "...";
        }
    }
}
