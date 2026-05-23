package io.github.lightrag.storage.arcadedb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ArcadeRecordMapper {
    private ArcadeRecordMapper() {
    }

    static String string(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? "" : value.toString();
    }

    static String nullableString(Map<String, Object> row, String key) {
        var value = row.get(key);
        return value == null ? null : value.toString();
    }

    static int integer(Map<String, Object> row, String key) {
        var value = Objects.requireNonNull(row.get(key), key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    static double decimal(Map<String, Object> row, String key) {
        var value = Objects.requireNonNull(row.get(key), key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    static boolean bool(Map<String, Object> row, String key) {
        var value = Objects.requireNonNull(row.get(key), key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    static Instant instant(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return Instant.parse(value.toString());
    }

    static Map<String, String> stringMap(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return Map.of();
        }
        return ArcadeJsonCodec.readStringMap(value);
    }

    static List<String> stringList(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return List.of();
        }
        return ArcadeJsonCodec.readStringList(value);
    }

    static List<Double> doubleList(Map<String, Object> row, String key) {
        var value = row.get(key);
        if (value == null) {
            return List.of();
        }
        return ArcadeJsonCodec.readDoubleList(value);
    }
}
