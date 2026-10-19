package io.github.lightrag.ontology;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.lightrag.api.GraphExtractionOptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * JSON codec for {@link Ontology} documents.
 *
 * <p>The on-wire document schema is versioned and contains a {@code kind}
 * discriminator:
 *
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "kind": "ontology",
 *   "entityTypes": [...],
 *   "relationTypes": [...],
 *   "version": "...",
 *   "metadata": {...}
 * }
 * }</pre>
 *
 * <p>Canonical JSON is suitable for content hashing: object keys are sorted
 * alphabetically, null values are preserved, numbers are kept as numbers, and
 * all text is normalized to Unicode NFC. The ontology {@code version} field and
 * any hash/audit metadata keys are excluded from the canonical form.
 */
public final class OntologyJsonCodec {

    /** Current document schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Document kind discriminator. */
    public static final String KIND = "ontology";

    private static final Set<String> EXCLUDED_HASH_AUDIT_KEYS = Set.of(
        "hash",
        "contentHash",
        "definitionHash",
        "candidateHash",
        "sha256",
        "md5",
        "audit",
        "createdAt",
        "updatedAt",
        "timestamp",
        "version"
    );

    private static final TypeReference<List<OntologyEntityType>> ENTITY_TYPE_LIST =
        new TypeReference<>() {};
    private static final TypeReference<List<OntologyRelationType>> RELATION_TYPE_LIST =
        new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP =
        new TypeReference<>() {};
    private static final TypeReference<GraphExtractionOptions> GRAPH_EXTRACTION_OPTIONS_TYPE =
        new TypeReference<>() {};
    private static final TypeReference<OntologyExtractionOverlay> ONTOLOGY_EXTRACTION_OVERLAY_TYPE =
        new TypeReference<>() {};

    private static final ObjectMapper MAPPER = createMapper(false);
    private static final ObjectMapper CANONICAL_MAPPER = createMapper(true);
    private static final ObjectMapper STRICT_MAPPER = createStrictMapper();

    private OntologyJsonCodec() {}

    private static ObjectMapper createMapper(boolean canonical) {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(OntologyPropertyType.class, new OntologyPropertyTypeSerializer());
        module.addDeserializer(OntologyPropertyType.class, new OntologyPropertyTypeDeserializer());
        mapper.registerModule(module);
        mapper.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        if (canonical) {
            mapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        }
        return mapper;
    }

    /**
     * Mapper for restoring persisted task-context snapshots. Scalar coercions are
     * disabled so corrupted field types (number for boolean, fractional number
     * for int, number for string) fail loudly instead of being silently
     * converted; numbers are also rejected for enums.
     */
    private static ObjectMapper createStrictMapper() {
        ObjectMapper mapper = createMapper(false);
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.coercionConfigFor(LogicalType.Boolean)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Integer)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        mapper.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    /**
     * Serializes an ontology to a versioned JSON document.
     *
     * @param ontology the ontology to serialize
     * @return JSON text
     */
    public static String write(Ontology ontology) {
        Objects.requireNonNull(ontology, "ontology");
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("kind", KIND);
        root.set("entityTypes", MAPPER.valueToTree(ontology.entityTypes()));
        root.set("relationTypes", MAPPER.valueToTree(ontology.relationTypes()));
        if (ontology.version() != null) {
            root.put("version", ontology.version());
        } else {
            root.putNull("version");
        }
        root.set("metadata", MAPPER.valueToTree(ontology.metadata()));
        return writeJson(root, MAPPER);
    }

    /**
     * Deserializes an ontology from a versioned JSON document.
     *
     * @param json JSON text
     * @return the parsed ontology
     * @throws IllegalArgumentException if the document is malformed or uses an
     *                                  unsupported schema version
     */
    public static Ontology read(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return readTree(MAPPER.readTree(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse ontology JSON", exception);
        }
    }

    /**
     * Deserializes an ontology from a parsed JSON tree.
     *
     * @param root the root document node
     * @return the parsed ontology
     * @throws IllegalArgumentException if the document is malformed or uses an
     *                                  unsupported schema version
     */
    public static Ontology readTree(JsonNode root) {
        Objects.requireNonNull(root, "root");
        if (!root.isObject()) {
            throw new IllegalArgumentException("Ontology document must be a JSON object");
        }

        int schemaVersion = root.path("schemaVersion").asInt(-1);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                "Unsupported ontology schema version: " + schemaVersion + " (supported: " + SCHEMA_VERSION + ")"
            );
        }
        String kind = root.path("kind").asText(null);
        if (!KIND.equals(kind)) {
            throw new IllegalArgumentException("Unsupported ontology kind: " + kind);
        }

        List<OntologyEntityType> entityTypes = readValue(root.path("entityTypes"), ENTITY_TYPE_LIST);
        List<OntologyRelationType> relationTypes = readValue(root.path("relationTypes"), RELATION_TYPE_LIST);
        String version = root.has("version") ? root.path("version").asText(null) : null;
        Map<String, String> metadata = readValue(root.path("metadata"), STRING_MAP);

        Ontology ontology = new Ontology(
            entityTypes == null ? List.of() : entityTypes,
            relationTypes == null ? List.of() : relationTypes,
            version,
            metadata == null ? Map.of() : metadata
        );
        return OntologyStructureValidator.validate(ontology);
    }

    /**
     * Returns the canonical JSON representation of the ontology, suitable for
     * content hashing.
     *
     * <p>The returned JSON has object keys sorted alphabetically, preserves null
     * values, keeps numbers as numbers, normalizes text to Unicode NFC, and
     * excludes the {@code version} field plus any hash/audit metadata keys.
     *
     * @param ontology the ontology to canonicalize
     * @return canonical JSON text
     */
    public static String canonicalJson(Ontology ontology) {
        Objects.requireNonNull(ontology, "ontology");
        Ontology canonical = ontology.toCanonical();

        ObjectNode content = CANONICAL_MAPPER.createObjectNode();
        content.put("schemaVersion", SCHEMA_VERSION);
        content.put("kind", KIND);
        content.set("entityTypes", CANONICAL_MAPPER.valueToTree(canonical.entityTypes()));
        content.set("relationTypes", CANONICAL_MAPPER.valueToTree(canonical.relationTypes()));

        ObjectNode metadata = CANONICAL_MAPPER.createObjectNode();
        for (Map.Entry<String, String> entry : canonical.metadata().entrySet()) {
            if (!EXCLUDED_HASH_AUDIT_KEYS.contains(entry.getKey())) {
                String normalizedKey = Normalizer.normalize(entry.getKey(), Normalizer.Form.NFC);
                if (metadata.has(normalizedKey)) {
                    throw new IllegalArgumentException(
                        "Canonical JSON contains duplicate object key after NFC normalization: " + normalizedKey
                    );
                }
                metadata.put(normalizedKey, entry.getValue());
            }
        }
        content.set("metadata", metadata);

        JsonNode normalized = normalizeAndSort(content);
        return writeJson(normalized, CANONICAL_MAPPER);
    }

    /**
     * Computes the SHA-256 content hash of the ontology.
     *
     * <p>The hash is computed over the canonical JSON form, excluding the
     * {@code version} field and any hash/audit metadata keys.
     *
     * @param ontology the ontology to hash
     * @return hex-encoded SHA-256 digest
     */
    public static String contentHash(Ontology ontology) {
        return sha256Hex(canonicalJson(ontology));
    }

    /**
     * Computes the SHA-256 hash of the complete ontology document, including the
     * {@code version} field and every metadata entry.
     *
     * <p>Unlike {@link #contentHash(Ontology)} this hash identifies the full
     * serialized base, so two bases that differ only in audit metadata or
     * version do not collide.
     *
     * @param ontology the ontology to hash
     * @return hex-encoded SHA-256 digest
     */
    public static String fullContentHash(Ontology ontology) {
        Objects.requireNonNull(ontology, "ontology");
        Ontology canonical = ontology.toCanonical();
        ObjectNode content = CANONICAL_MAPPER.createObjectNode();
        content.put("schemaVersion", SCHEMA_VERSION);
        content.put("kind", KIND);
        content.set("entityTypes", CANONICAL_MAPPER.valueToTree(canonical.entityTypes()));
        content.set("relationTypes", CANONICAL_MAPPER.valueToTree(canonical.relationTypes()));
        if (canonical.version() == null) {
            content.putNull("version");
        } else {
            content.put("version", canonical.version());
        }
        content.set("metadata", CANONICAL_MAPPER.valueToTree(canonical.metadata()));
        return sha256Hex(writeJson(normalizeAndSort(content), CANONICAL_MAPPER));
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available", exception);
        }
    }

    /**
     * Returns a stable fingerprint of an ontology task context.
     *
     * <p>The fingerprint covers the ontology content, overlay content,
     * extraction options, and the unknown-keyword policy so that any change
     * in the effective schema or configuration produces a different value.
     */
    public static String contextFingerprint(OntologyTaskContext context) {
        Objects.requireNonNull(context, "context");
        try {
            String canonical = writeJson(normalizeAndSort(contextToJson(context)), CANONICAL_MAPPER);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm not available", exception);
        }
    }

    /**
     * Serializes a task context to its canonical JSON representation.
     *
     * <p>The returned JSON uses schemaVersion 1 and can be recovered with
     * {@link #readContext(String)}.
     */
    public static String writeContext(OntologyTaskContext context) {
        Objects.requireNonNull(context, "context");
        return writeJson(normalizeAndSort(contextToJson(context)), CANONICAL_MAPPER);
    }

    /**
     * Deserializes a task context from its canonical JSON representation.
     *
     * @param json canonical context JSON
     * @return the recovered context
     * @throws IllegalArgumentException if the JSON is malformed or the schema version is unsupported
     */
    public static OntologyTaskContext readContext(String json) {
        Objects.requireNonNull(json, "json");
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Context document must be a JSON object");
            }
            JsonNode versionNode = root.get("schemaVersion");
            if (versionNode == null
                || !versionNode.isNumber()
                || versionNode.isFloatingPointNumber()
                || !versionNode.canConvertToInt()
                || versionNode.asInt() != 1) {
                throw new IllegalArgumentException(
                    "Unsupported context schema version: "
                        + (versionNode == null ? "<missing>" : versionNode.asText())
                );
            }
            JsonNode optionsNode = root.path("graphExtractionOptions");
            JsonNode ontologyNode = root.path("ontology");
            JsonNode overlayNode = root.path("overlay");
            if (!optionsNode.isObject()) {
                throw new IllegalArgumentException(
                    "Context document must contain a graphExtractionOptions object"
                );
            }
            if (!ontologyNode.isObject()) {
                throw new IllegalArgumentException("Context document must contain an ontology object");
            }
            if (!overlayNode.isObject()) {
                throw new IllegalArgumentException("Context document must contain an overlay object");
            }
            JsonNode dropNode = root.get("dropOnUnknownKeyword");
            if (dropNode == null || !dropNode.isBoolean()) {
                throw new IllegalArgumentException(
                    "Context document must contain a boolean dropOnUnknownKeyword"
                );
            }
            requireSnapshotField(ontologyNode, "entityTypes", JsonNode::isArray, "Context ontology");
            requireSnapshotField(ontologyNode, "relationTypes", JsonNode::isArray, "Context ontology");
            requireSnapshotField(
                ontologyNode,
                "version",
                node -> node.isNull() || node.isTextual(),
                "Context ontology"
            );
            requireSnapshotField(ontologyNode, "metadata", JsonNode::isObject, "Context ontology");
            requireSnapshotField(
                overlayNode,
                "mode",
                node -> node.isTextual(),
                "Context overlay"
            );
            requireSnapshotField(overlayNode, "patterns", JsonNode::isObject, "Context overlay");
            requireSnapshotField(overlayNode, "attributeBlocks", JsonNode::isObject, "Context overlay");
            GraphExtractionOptions graphExtractionOptions = readValue(
                optionsNode, GRAPH_EXTRACTION_OPTIONS_TYPE, STRICT_MAPPER
            );
            requireCompleteSerializedFields(optionsNode, graphExtractionOptions, "graphExtractionOptions");
            Ontology ontology = readEmbeddedOntology(ontologyNode);
            OntologyExtractionOverlay overlay = readValue(
                overlayNode, ONTOLOGY_EXTRACTION_OVERLAY_TYPE, STRICT_MAPPER
            );
            return new OntologyTaskContext(
                graphExtractionOptions == null ? GraphExtractionOptions.defaults() : graphExtractionOptions,
                ontology,
                overlay,
                dropNode.asBoolean()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse context JSON", exception);
        }
    }

    private static void requireSnapshotField(
        JsonNode node,
        String field,
        java.util.function.Predicate<JsonNode> typeCheck,
        String section
    ) {
        JsonNode value = node.get(field);
        if (value == null || !typeCheck.test(value)) {
            throw new IllegalArgumentException(section + " must contain a valid " + field + " field");
        }
    }

    private static void requireCompleteSerializedFields(JsonNode node, Object snapshot, String section) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Context " + section + " must not be null");
        }
        var fieldNames = MAPPER.valueToTree(snapshot).fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!node.has(field)) {
                throw new IllegalArgumentException(
                    "Context " + section + " is missing field: " + field
                );
            }
        }
    }

    private static ObjectNode contextToJson(OntologyTaskContext context) {
        ObjectNode root = CANONICAL_MAPPER.createObjectNode();
        root.put("schemaVersion", 1);
        root.set("ontology", CANONICAL_MAPPER.valueToTree(context.ontology().toCanonical()));
        root.set("overlay", CANONICAL_MAPPER.valueToTree(context.overlay()));
        root.set("graphExtractionOptions", CANONICAL_MAPPER.valueToTree(context.graphExtractionOptions()));
        root.put("dropOnUnknownKeyword", context.dropOnUnknownKeyword());
        return root;
    }

    private static <T> T readValue(JsonNode node, TypeReference<T> typeReference) {
        return readValue(node, typeReference, MAPPER);
    }

    private static <T> T readValue(JsonNode node, TypeReference<T> typeReference, ObjectMapper mapper) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return mapper.treeToValue(node, typeReference);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to deserialize ontology field", exception);
        }
    }

    /**
     * Reads the embedded ontology snapshot of a context document. Unlike
     * {@link #readTree(JsonNode)} the embedded node carries no schema/kind
     * envelope; the enclosing context document owns the schema version.
     */
    private static Ontology readEmbeddedOntology(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return new Ontology(null, null, null, null);
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Context ontology must be a JSON object");
        }
        List<OntologyEntityType> entityTypes = readValue(node.path("entityTypes"), ENTITY_TYPE_LIST, STRICT_MAPPER);
        List<OntologyRelationType> relationTypes = readValue(
            node.path("relationTypes"), RELATION_TYPE_LIST, STRICT_MAPPER
        );
        String version = node.has("version") ? node.path("version").asText(null) : null;
        Map<String, String> metadata = readValue(node.path("metadata"), STRING_MAP, STRICT_MAPPER);
        return OntologyStructureValidator.validate(new Ontology(
            entityTypes == null ? List.of() : entityTypes,
            relationTypes == null ? List.of() : relationTypes,
            version,
            metadata == null ? Map.of() : metadata
        ));
    }

    private static String writeJson(JsonNode node, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize ontology JSON", exception);
        }
    }

    private static JsonNode normalizeAndSort(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isTextual()) {
            String text = node.asText();
            String normalized = Normalizer.normalize(text, Normalizer.Form.NFC);
            return TextNode.valueOf(normalized);
        }
        if (node.isObject()) {
            ObjectNode sorted = CANONICAL_MAPPER.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.sort(java.util.Comparator.comparing(name -> Normalizer.normalize(name, Normalizer.Form.NFC)));
            Set<String> seen = new HashSet<>();
            for (String fieldName : fieldNames) {
                String normalized = Normalizer.normalize(fieldName, Normalizer.Form.NFC);
                if (!seen.add(normalized)) {
                    throw new IllegalArgumentException(
                        "Canonical JSON contains duplicate object key after NFC normalization: " + normalized
                    );
                }
                sorted.set(normalized, normalizeAndSort(node.get(fieldName)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = CANONICAL_MAPPER.createArrayNode();
            for (JsonNode element : node) {
                array.add(normalizeAndSort(element));
            }
            return array;
        }
        return node;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static final class OntologyPropertyTypeSerializer extends JsonSerializer<OntologyPropertyType> {
        @Override
        public void serialize(
            OntologyPropertyType value,
            JsonGenerator generator,
            SerializerProvider serializers
        ) throws IOException {
            generator.writeString(value.name().toLowerCase(Locale.ROOT));
        }
    }

    private static final class OntologyPropertyTypeDeserializer extends JsonDeserializer<OntologyPropertyType> {
        @Override
        public OntologyPropertyType deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return OntologyPropertyType.normalize(parser.getValueAsString());
        }
    }
}
