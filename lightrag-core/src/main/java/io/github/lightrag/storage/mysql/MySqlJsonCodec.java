package io.github.lightrag.storage.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.ontology.AttributeJsonCodec;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MySqlJsonCodec {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<DocumentGraphSnapshotStore.ExtractedEntityRecord>> EXTRACTED_ENTITY_LIST =
        new TypeReference<>() {
        };
    private static final TypeReference<List<DocumentGraphSnapshotStore.ExtractedRelationRecord>> EXTRACTED_RELATION_LIST =
        new TypeReference<>() {
        };

    private MySqlJsonCodec() {
    }

    public static String writeStringMap(Map<String, String> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static Map<String, String> readStringMap(String value) {
        return readJson(value, STRING_MAP);
    }

    public static String writeObjectMap(Map<String, Object> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static Map<String, Object> readObjectMap(String value) {
        return readJson(value, OBJECT_MAP);
    }

    public static String writeStringList(List<String> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static List<String> readStringList(String value) {
        return readJson(value, STRING_LIST);
    }

    public static String writeExtractedEntityList(List<DocumentGraphSnapshotStore.ExtractedEntityRecord> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static List<DocumentGraphSnapshotStore.ExtractedEntityRecord> readExtractedEntityList(String value) {
        return readJson(value, EXTRACTED_ENTITY_LIST).stream()
            .map(entity -> new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                entity.name(),
                entity.type(),
                entity.description(),
                entity.aliases(),
                canonicalAttributes(entity.attributes())
            ))
            .toList();
    }

    public static String writeExtractedRelationList(List<DocumentGraphSnapshotStore.ExtractedRelationRecord> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static List<DocumentGraphSnapshotStore.ExtractedRelationRecord> readExtractedRelationList(String value) {
        return readJson(value, EXTRACTED_RELATION_LIST).stream()
            .map(relation -> new DocumentGraphSnapshotStore.ExtractedRelationRecord(
                relation.sourceEntityName(),
                relation.targetEntityName(),
                relation.keywords(),
                relation.description(),
                relation.weight(),
                canonicalAttributes(relation.attributes())
            ))
            .toList();
    }

    /**
     * Attribute bags are canonical JSON values: integral numbers are {@link Long}
     * and fractional numbers are {@link Double}. Plain JSON deserialization yields
     * {@link Integer} for small integrals, so re-encoding through the codec keeps
     * reloaded bags identical to the saved values. Other maps (document status
     * metadata, for example) keep their plain JSON types.
     */
    private static Map<String, Object> canonicalAttributes(Map<String, Object> attributes) {
        return AttributeJsonCodec.fromJson(AttributeJsonCodec.toJson(attributes));
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON value", exception);
        }
    }

    private static <T> T readJson(String value, TypeReference<T> typeReference) {
        try {
            return OBJECT_MAPPER.readValue(Objects.requireNonNull(value, "value"), typeReference);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to deserialize JSON value", exception);
        }
    }
}
