package io.github.lightrag.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.indexing.ChunkingStrategyOverride;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record ChunkOptions(Map<String, Object> values) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public ChunkOptions {
        values = copyMap(values);
    }

    public static ChunkOptions empty() {
        return new ChunkOptions(Map.of());
    }

    public static ChunkOptions from(Map<String, ?> values) {
        return new ChunkOptions(values == null ? Map.of() : copyMap(values));
    }

    public static ChunkOptions slim(Map<String, ?> values, ProcessOptions processOptions) {
        var source = values == null ? Map.<String, Object>of() : copyMap(values);
        var result = new LinkedHashMap<String, Object>();
        if (source.containsKey("chunk_token_size")) {
            result.put("chunk_token_size", source.get("chunk_token_size"));
        }
        var strategyKey = strategyKey(Objects.requireNonNull(processOptions, "processOptions").chunkingStrategy());
        var selected = source.get(strategyKey);
        result.put(strategyKey, selected instanceof Map<?, ?> map ? copyMap(map) : Map.of());
        return new ChunkOptions(result);
    }

    public static ChunkOptions forProcessOptions(ProcessOptions processOptions) {
        var strategyKey = strategyKey(Objects.requireNonNull(processOptions, "processOptions").chunkingStrategy());
        return new ChunkOptions(Map.of(strategyKey, Map.of()));
    }

    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("chunk_options must be JSON serializable", exception);
        }
    }

    private static String strategyKey(ChunkingStrategyOverride strategy) {
        return switch (Objects.requireNonNull(strategy, "strategy")) {
            case RECURSIVE -> "recursive_character";
            case SEMANTIC_VECTOR -> "semantic_vector";
            case PARAGRAPH -> "paragraph_semantic";
            case FIXED, AUTO, SMART, REGEX -> "fixed_token";
        };
    }

    private static Map<String, Object> copyMap(Map<?, ?> source) {
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : Objects.requireNonNull(source, "values").entrySet()) {
            copy.put(normalizeKey(entry.getKey()), copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeKey(Object key) {
        return Objects.requireNonNull(key, "chunk_options key").toString().replace('-', '_');
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        if (value instanceof Iterable<?> iterable) {
            var list = new java.util.ArrayList<Object>();
            for (var item : iterable) {
                list.add(copyValue(item));
            }
            return Collections.unmodifiableList(list);
        }
        return value;
    }
}
