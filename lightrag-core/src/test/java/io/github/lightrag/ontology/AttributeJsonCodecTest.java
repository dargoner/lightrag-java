package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeJsonCodecTest {

    @Test
    void producesCanonicalJsonWithSortedKeysAndNoWhitespace() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("z", 1);
        attributes.put("a", "x");
        attributes.put("m", List.of(true, false));

        assertThat(AttributeJsonCodec.toJson(attributes))
            .isEqualTo("{\"a\":\"x\",\"m\":[true,false],\"z\":1}");
    }

    @Test
    void omitsNullValuesAndNullMaps() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("a", 1);
        attributes.put("b", null);

        assertThat(AttributeJsonCodec.toJson(attributes)).isEqualTo("{\"a\":1}");
        assertThat(AttributeJsonCodec.toJson(null)).isEqualTo("{}");
        assertThat(AttributeJsonCodec.toJson(Map.of())).isEqualTo("{}");
    }

    @Test
    void encodesUnicodeVerbatimAndDeterministically() {
        Map<String, Object> attributes = Map.of("名称", "汉");

        String first = AttributeJsonCodec.toJson(attributes);
        String second = AttributeJsonCodec.toJson(attributes);

        assertThat(first).isEqualTo(second).contains("汉");
    }

    @Test
    void decodesNullBlankAndMalformedInputAsEmptyMap() {
        assertThat(AttributeJsonCodec.fromJson(null)).isEmpty();
        assertThat(AttributeJsonCodec.fromJson("   ")).isEmpty();
        assertThat(AttributeJsonCodec.fromJson("{not json")).isEmpty();
        assertThat(AttributeJsonCodec.fromJson("[1,2]")).isEmpty();
        assertThat(AttributeJsonCodec.fromJson("\"text\"")).isEmpty();
    }

    @Test
    void roundTripsAndNormalizesIntegralNumbersToLong() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("count", 7L);
        attributes.put("ratio", 1.5d);
        attributes.put("flags", List.of(true, false));
        attributes.put("name", "x");

        Map<String, Object> decoded = AttributeJsonCodec.fromJson(
            AttributeJsonCodec.toJson(attributes));

        assertThat(decoded).containsExactly(
            Map.entry("count", 7L),
            Map.entry("flags", List.of(true, false)),
            Map.entry("name", "x"),
            Map.entry("ratio", 1.5d)
        );
        assertThat(decoded.get("count")).isInstanceOf(Long.class);
    }

    @Test
    void decodingSortsKeysAndRejectsNestedObjectsOrArrays() {
        Map<String, Object> decoded = AttributeJsonCodec.fromJson(
            "{\"z\":1,\"a\":\"x\",\"nested\":{\"k\":1},\"nestedList\":[[1]],\"list\":[1,\"b\"]}");

        assertThat(decoded.keySet()).containsExactly("a", "list", "z");
        assertThat(decoded.get("list")).isEqualTo(List.of(1L, "b"));
    }

    @Test
    void decodedMapIsUnmodifiable() {
        Map<String, Object> decoded = AttributeJsonCodec.fromJson("{\"a\":1}");

        assertThat(decoded).containsExactly(Map.entry("a", 1L));
        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> decoded.put("b", 2))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
