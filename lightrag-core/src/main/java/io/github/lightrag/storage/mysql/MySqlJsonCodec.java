package io.github.lightrag.storage.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MySqlJsonCodec {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
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
        return readJson(value, EXTRACTED_ENTITY_LIST);
    }

    public static String writeExtractedRelationList(List<DocumentGraphSnapshotStore.ExtractedRelationRecord> value) {
        return writeJson(Objects.requireNonNull(value, "value"));
    }

    public static List<DocumentGraphSnapshotStore.ExtractedRelationRecord> readExtractedRelationList(String value) {
        return readJson(value, EXTRACTED_RELATION_LIST);
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
