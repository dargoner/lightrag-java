package io.github.lightrag.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Codec for the canonical JSON form of a materialized attribute bag.
 *
 * <p>The canonical form is deterministic: keys are sorted in natural string
 * order, there is no insignificant whitespace, and {@code null} values are
 * never written. The encoded string is the only supported comparison and
 * hashing form of an attribute bag; byte size limits are computed externally on
 * its UTF-8 representation.
 *
 * <p>Decoding is tolerant: {@code null}, blank, malformed, or non-object input
 * yields an empty map and a warning, so a single corrupt stored value never
 * blocks reads. Integral numbers are normalized to {@link Long}, fractional
 * numbers to {@link Double}; values that cannot be represented as a scalar or a
 * list of scalars are dropped.
 */
public final class AttributeJsonCodec {

    private static final Logger log = LoggerFactory.getLogger(AttributeJsonCodec.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private AttributeJsonCodec() {}

    /**
     * Encodes an attribute map into canonical JSON.
     *
     * @param attributes the attributes; {@code null} and {@code null} values are
     *                   ignored
     * @return the canonical JSON object, never {@code null}
     */
    public static String toJson(Map<String, Object> attributes) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (attributes != null) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            return MAPPER.writeValueAsString(sorted);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode attribute JSON", ex);
        }
    }

    /**
     * Decodes canonical JSON into an attribute map.
     *
     * @param json the stored JSON; may be {@code null} or blank
     * @return a sorted, unmodifiable map; empty when the input is missing or
     *         cannot be decoded
     */
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(json);
        } catch (JsonProcessingException ex) {
            var location = ex.getLocation();
            log.warn(
                "Ignoring malformed attribute JSON ({}, line {}, column {})",
                ex.getClass().getSimpleName(),
                location == null ? -1 : location.getLineNr(),
                location == null ? -1 : location.getColumnNr()
            );
            return Map.of();
        }
        if (node == null || !node.isObject()) {
            log.warn("Ignoring attribute JSON that is not an object");
            return Map.of();
        }
        TreeMap<String, Object> result = new TreeMap<>();
        node.fields().forEachRemaining(entry -> {
            Object value = decodeNode(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * Canonical JSON of a single attribute value, used by the duplicate-key
     * ruling to compare values that have already been coerced.
     */
    static String canonicalValueJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode attribute value JSON", ex);
        }
    }

    private static Object decodeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() ? (Object) node.longValue() : null;
        }
        if (node.isFloatingPointNumber()) {
            double value = node.doubleValue();
            return Double.isFinite(value) ? (Object) value : null;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                if (element == null || element.isArray() || element.isObject()) {
                    return null;
                }
                Object value = decodeNode(element);
                if (value == null) {
                    return null;
                }
                values.add(value);
            }
            return List.copyOf(values);
        }
        return null;
    }
}
