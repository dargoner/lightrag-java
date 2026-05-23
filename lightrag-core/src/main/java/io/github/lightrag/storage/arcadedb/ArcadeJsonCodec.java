package io.github.lightrag.storage.arcadedb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ArcadeJsonCodec {
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new Jdk8Module());

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<DocumentGraphSnapshotStore.ExtractedEntityRecord>> EXTRACTED_ENTITY_LIST =
        new TypeReference<>() {
        };
    private static final TypeReference<List<DocumentGraphSnapshotStore.ExtractedRelationRecord>> EXTRACTED_RELATION_LIST =
        new TypeReference<>() {
        };

    private ArcadeJsonCodec() {
    }

    static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Objects.requireNonNull(value, "value"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON value", exception);
        }
    }

    static Map<String, String> readStringMap(Object value) {
        return convert(value, STRING_MAP);
    }

    static Map<String, Object> readObjectMap(Object value) {
        return convert(value, OBJECT_MAP);
    }

    static List<String> readStringList(Object value) {
        return convert(value, STRING_LIST);
    }

    static List<Double> readDoubleList(Object value) {
        return convert(value, DOUBLE_LIST);
    }

    static String writeStringMap(Map<String, String> value) {
        return writeJson(value);
    }

    static String writeStringList(List<String> value) {
        return writeJson(value);
    }

    static String writeDoubleList(List<Double> value) {
        return writeJson(value);
    }

    static String writeExtractedEntityRecordList(List<DocumentGraphSnapshotStore.ExtractedEntityRecord> value) {
        return writeJson(value);
    }

    static String writeExtractedRelationRecordList(List<DocumentGraphSnapshotStore.ExtractedRelationRecord> value) {
        return writeJson(value);
    }

    static List<DocumentGraphSnapshotStore.ExtractedEntityRecord> readExtractedEntityRecordList(Object value) {
        return convert(value, EXTRACTED_ENTITY_LIST);
    }

    static List<DocumentGraphSnapshotStore.ExtractedRelationRecord> readExtractedRelationRecordList(Object value) {
        return convert(value, EXTRACTED_RELATION_LIST);
    }

    static <T> T convert(Object value, Class<T> targetType) {
        return OBJECT_MAPPER.convertValue(value, targetType);
    }

    private static <T> T convert(Object value, TypeReference<T> typeReference) {
        Objects.requireNonNull(value, "value");
        if (value instanceof String stringValue) {
            try {
                return OBJECT_MAPPER.readValue(stringValue, typeReference);
            } catch (IOException exception) {
                throw new IllegalArgumentException("Failed to deserialize JSON value", exception);
            }
        }
        return OBJECT_MAPPER.convertValue(value, typeReference);
    }
}
