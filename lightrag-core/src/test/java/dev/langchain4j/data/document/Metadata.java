package dev.langchain4j.data.document;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Metadata {
    private final Map<String, Object> values;

    public Metadata(Map<String, Object> values) {
        this.values = Map.copyOf(new LinkedHashMap<>(values));
    }

    public static Metadata from(Map<String, ?> values) {
        return new Metadata(new LinkedHashMap<>(values));
    }

    public Map<String, Object> asMap() {
        return values;
    }
}
