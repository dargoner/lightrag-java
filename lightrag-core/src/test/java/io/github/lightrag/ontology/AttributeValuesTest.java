package io.github.lightrag.ontology;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttributeValuesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void sanitizeRawKeepsBothCandidatesForTrimmedKeyCollisionInMap() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("Age", 1);
        raw.put(" Age ", 2);

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates())
            .extracting(RawAttribute::rawKey)
            .containsExactly("Age", "Age");
        assertThat(result.candidates())
            .extracting(RawAttribute::value)
            .containsExactly(1L, 2L);
    }

    @Test
    void sanitizeRawKeepsBothCandidatesForTrimmedKeyCollisionInJson() {
        RawAttributes result = AttributeValues.sanitizeRaw(json("{\"Age\":1,\" Age \":2}"));

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates())
            .extracting(RawAttribute::rawKey)
            .containsExactly("Age", "Age");
        assertThat(result.candidates())
            .extracting(RawAttribute::value)
            .containsExactly(1L, 2L);
    }

    @Test
    void sanitizeRawRejectsBlankLongAndControlCharacterKeys() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("", 1);
        raw.put("   ", 2);
        raw.put("bad\nkey", 3);
        raw.put("x".repeat(129), 4);
        raw.put("y".repeat(128), 5);

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).rawKey()).isEqualTo("y".repeat(128));
        assertThat(result.issues())
            .extracting(RawIssue::rawKey, RawIssue::reason)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY),
                org.assertj.core.groups.Tuple.tuple("", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY),
                org.assertj.core.groups.Tuple.tuple("bad\nkey", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY),
                org.assertj.core.groups.Tuple.tuple(
                    "x".repeat(129), UnmatchedLabel.Reason.INVALID_ATTRIBUTE_KEY)
            );
    }

    @Test
    void sanitizeRawDiscardsWholeObjectAboveRawCandidateLimit() {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < AttributeValues.MAX_RAW_CANDIDATES + 1; i++) {
            raw.put("k" + i, i);
        }

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).rawKey()).isEmpty();
        assertThat(result.issues().get(0).reason())
            .isEqualTo(UnmatchedLabel.Reason.ATTRIBUTE_LIMIT_EXCEEDED);
    }

    @Test
    void sanitizeRawDoesNotApplySemanticCaps() {
        List<Object> longList = new ArrayList<>();
        for (int i = 0; i < AttributeValues.MAX_LIST_ELEMENTS + 20; i++) {
            longList.add("e" + i);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("list", longList);
        raw.put("text", "x".repeat(AttributeValues.MAX_STRING_LENGTH + 100));
        for (int i = 0; i < AttributeValues.MAX_KEYS + 4; i++) {
            raw.put("key" + i, i);
        }

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.issues()).isEmpty();
        assertThat(result.candidates()).hasSize(AttributeValues.MAX_KEYS + 6);
        assertThat((List<?>) result.candidates().get(0).value())
            .hasSize(AttributeValues.MAX_LIST_ELEMENTS + 20);
    }

    @Test
    void sanitizeRawRejectsOversizedStringAndNestedStructures() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("tooLong", "x".repeat(AttributeValues.MAX_RAW_STRING_BYTES + 1));
        raw.put("nestedMap", Map.of("a", 1));
        raw.put("nestedList", List.of(List.of(1)));

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues())
            .extracting(RawIssue::rawKey, RawIssue::reason)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                    "tooLong", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE),
                org.assertj.core.groups.Tuple.tuple(
                    "nestedMap", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE),
                org.assertj.core.groups.Tuple.tuple(
                    "nestedList", UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE)
            );
    }

    @Test
    void sanitizeRawRejectsNonObjectJsonAndAcceptsMissingJson() {
        assertThat(AttributeValues.sanitizeRaw((JsonNode) null).candidates()).isEmpty();
        assertThat(AttributeValues.sanitizeRaw(json("null")).issues()).isEmpty();

        RawAttributes result = AttributeValues.sanitizeRaw(json("[1,2]"));
        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).reason())
            .isEqualTo(UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE);
    }

    @Test
    void sanitizeRawKeepsExactDecimalsBeforeIntegerCoercion() {
        RawAttributes result = AttributeValues.sanitizeRaw(
            json("{\"age\":1.99999999999999999}")
        );

        Object value = result.candidates().get(0).value();
        assertThat(value).isInstanceOf(BigDecimal.class);
        assertThat(AttributeValues.coerce(value, OntologyPropertyType.INTEGER)).isEqualTo(1L);
    }

    @Test
    void sanitizeRawRejectsIntegerMagnitudesAboveTheDoubleExactRange() {
        RawAttributes result = AttributeValues.sanitizeRaw(
            json("{\"age\":9007199254740993.0}")
        );

        assertThatThrownBy(() -> AttributeValues.coerce(
            result.candidates().get(0).value(),
            OntologyPropertyType.INTEGER
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2^53");
    }

    @Test
    void sanitizeRawCanonicalizesJavaNumbersToLongAndDouble() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("decimalIntegral", new BigDecimal("1.0"));
        raw.put("decimalFractional", new BigDecimal("1.5"));
        raw.put("bigInteger", new BigInteger("7"));
        raw.put("doubleIntegral", 2.0d);
        raw.put("list", List.of(new BigDecimal("3.0"), new BigDecimal("3.5")));

        RawAttributes result = AttributeValues.sanitizeRaw(raw);

        assertThat(result.candidates())
            .extracting(RawAttribute::value)
            .containsExactly(1L, 1.5d, 7L, 2L, List.of(3L, 3.5d));
    }

    @Test
    void canonicalNumbersOutsideLongUseTheSameDoubleTheReadPathProduces() {
        RawAttributes result = AttributeValues.sanitizeRaw(Map.of(
            "wide", new BigDecimal("9223372036854775808"),
            "huge", new BigDecimal("1E+20")
        ));

        assertThat(result.candidates())
            .extracting(RawAttribute::value)
            .containsExactlyInAnyOrder(9.223372036854776E18, 1.0E20);
        assertThat(result.candidates())
            .extracting(RawAttribute::value)
            .allMatch(value -> value instanceof Long || value instanceof Double);
    }

    @Test
    void canonicalNumbersRejectMagnitudesThatOverflowDouble() {
        RawAttributes result = AttributeValues.sanitizeRaw(Map.of(
            "huge", new BigDecimal("1E+400")
        ));

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue ->
            assertThat(issue.reason()).isEqualTo(UnmatchedLabel.Reason.INVALID_ATTRIBUTE_VALUE));
    }

    @Test
    void coercesStrings() {
        assertThat(AttributeValues.coerce("  hello  ", OntologyPropertyType.STRING))
            .isEqualTo("hello");
        assertThat(AttributeValues.coerce(42, OntologyPropertyType.STRING)).isEqualTo("42");
        assertThat(AttributeValues.coerce(true, OntologyPropertyType.STRING)).isEqualTo("true");
        assertThatThrownBy(() -> AttributeValues.coerce("   ", OntologyPropertyType.STRING))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(List.of("a"), OntologyPropertyType.STRING))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coercesIntegersThroughBigDecimalWithoutDoubleDetour() {
        assertThat(AttributeValues.coerce("1.9", OntologyPropertyType.INTEGER)).isEqualTo(1L);
        assertThat(AttributeValues.coerce("-1.9", OntologyPropertyType.INTEGER)).isEqualTo(-1L);
        assertThat(AttributeValues.coerce(new BigDecimal("42.999"), OntologyPropertyType.INTEGER))
            .isEqualTo(42L);
        assertThat(AttributeValues.coerce(2.9d, OntologyPropertyType.INTEGER)).isEqualTo(2L);
    }

    @Test
    void coercesIntegerBoundaries() {
        long max = 1L << 53;
        assertThat(AttributeValues.coerce(Long.toString(max), OntologyPropertyType.INTEGER))
            .isEqualTo(max);
        assertThat(AttributeValues.coerce(Long.toString(-max), OntologyPropertyType.INTEGER))
            .isEqualTo(-max);

        assertThatThrownBy(() -> AttributeValues.coerce(max + 1, OntologyPropertyType.INTEGER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2^53");
        assertThatThrownBy(() -> AttributeValues.coerce(Long.MAX_VALUE, OntologyPropertyType.INTEGER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce("not-a-number", OntologyPropertyType.INTEGER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(true, OntologyPropertyType.INTEGER))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coercesFloats() {
        assertThat(AttributeValues.coerce("1.5", OntologyPropertyType.FLOAT)).isEqualTo(1.5d);
        assertThat(AttributeValues.coerce(2L, OntologyPropertyType.FLOAT)).isEqualTo(2.0d);
        assertThatThrownBy(() -> AttributeValues.coerce(" NaN ", OntologyPropertyType.FLOAT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(
            Double.POSITIVE_INFINITY, OntologyPropertyType.FLOAT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(true, OntologyPropertyType.FLOAT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coercesBooleans() {
        assertThat(AttributeValues.coerce(true, OntologyPropertyType.BOOLEAN)).isEqualTo(true);
        assertThat(AttributeValues.coerce("TRUE", OntologyPropertyType.BOOLEAN)).isEqualTo(true);
        assertThat(AttributeValues.coerce(" no ", OntologyPropertyType.BOOLEAN)).isEqualTo(false);
        assertThat(AttributeValues.coerce(0, OntologyPropertyType.BOOLEAN)).isEqualTo(false);
        assertThat(AttributeValues.coerce(2, OntologyPropertyType.BOOLEAN)).isEqualTo(true);
        assertThatThrownBy(() -> AttributeValues.coerce("maybe", OntologyPropertyType.BOOLEAN))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coercesDates() {
        assertThat(AttributeValues.coerce("2024-01-02", OntologyPropertyType.DATE))
            .isEqualTo("2024-01-02");
        assertThat(AttributeValues.coerce("2024-01-02T03:04:05Z", OntologyPropertyType.DATE))
            .isEqualTo("2024-01-02");
        assertThat(AttributeValues.coerce("2024-01-02T03:04:05+08:00", OntologyPropertyType.DATE))
            .isEqualTo("2024-01-02");
        assertThat(AttributeValues.coerce(" 2024-12-31 ", OntologyPropertyType.DATE))
            .isEqualTo("2024-12-31");
        assertThatThrownBy(() -> AttributeValues.coerce("2024-13-01", OntologyPropertyType.DATE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce("not-a-date", OntologyPropertyType.DATE))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(20240102, OntologyPropertyType.DATE))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coercesLists() {
        assertThat(AttributeValues.coerce(List.of(1, 2), OntologyPropertyType.LIST))
            .isEqualTo(List.of(1L, 2L));
        assertThat(AttributeValues.coerce(5, OntologyPropertyType.LIST))
            .isEqualTo(List.of(5L));
        assertThat(AttributeValues.coerce(new BigDecimal("1.5"), OntologyPropertyType.LIST))
            .isEqualTo(List.of(1.5d));
        assertThatThrownBy(() -> AttributeValues.coerce(
            List.of(List.of(1)), OntologyPropertyType.LIST))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(
            Map.of("a", 1), OntologyPropertyType.LIST))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AttributeValues.coerce(
            java.util.Arrays.asList("a", null), OntologyPropertyType.LIST))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyCapsDropsKeysBeyondTheBagLimit() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < AttributeValues.MAX_KEYS + 6; i++) {
            values.put(String.format("k%02d", i), "v" + i);
        }

        Map<String, Object> capped = AttributeValues.applyCaps(values, Set.of());

        assertThat(capped).hasSize(AttributeValues.MAX_KEYS);
        assertThat(capped.keySet().iterator().next()).isEqualTo("k00");
        assertThat(capped).doesNotContainKey("k64");
    }

    @Test
    void applyCapsPrefersDeclaredIdentities() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < AttributeValues.MAX_KEYS; i++) {
            values.put(String.format("a%02d", i), i);
        }
        values.put("zzz", "undeclared");

        Map<String, Object> capped = AttributeValues.applyCaps(values, values.keySet()
            .stream()
            .filter(key -> !"zzz".equals(key))
            .collect(java.util.stream.Collectors.toUnmodifiableSet()));

        assertThat(capped).hasSize(AttributeValues.MAX_KEYS);
        assertThat(capped).containsKey("a63");
        assertThat(capped).doesNotContainKey("zzz");
    }

    @Test
    void applyCapsMeasuresCanonicalJsonInUtf8Bytes() {
        String multibyte = "汉".repeat(AttributeValues.MAX_STRING_LENGTH);
        Map<String, Object> values = Map.of("a", multibyte, "b", multibyte);

        Map<String, Object> capped = AttributeValues.applyCaps(values, Set.of("a", "b"));

        // One value is ~12 KiB of UTF-8; two would exceed 16 KiB even though the
        // combined character count is below the byte limit.
        assertThat(capped).containsOnlyKeys("a");
    }

    @Test
    void applyCapsEnforcesListAndStringValueCaps() {
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < AttributeValues.MAX_LIST_ELEMENTS + 1; i++) {
            tooMany.add("e");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("listTooLong", tooMany);
        values.put("elementTooLong", List.of("x".repeat(
            AttributeValues.MAX_LIST_ELEMENT_STRING_LENGTH + 1)));
        values.put("stringTooLong", "x".repeat(AttributeValues.MAX_STRING_LENGTH + 1));
        values.put("kept", "ok");

        Map<String, Object> capped = AttributeValues.applyCaps(values, Set.of());

        assertThat(capped).containsOnlyKeys("kept");
    }

    @Test
    void mergeOverwritesPerKeyAndSorts() {
        Map<String, Object> merged = AttributeValues.merge(
            Map.of("b", 2, "a", 1),
            Map.of("a", 3, "c", 4)
        );

        assertThat(merged).containsExactly(
            Map.entry("a", 3),
            Map.entry("b", 2),
            Map.entry("c", 4)
        );
        assertThat(merged.keySet()).containsExactly("a", "b", "c");
        assertThatThrownBy(() -> merged.put("d", 5))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mergeIgnoresNullValues() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("a", 1);
        base.put("b", null);

        Map<String, Object> merged = AttributeValues.merge(base, Map.of("c", 3));

        assertThat(merged).containsExactly(Map.entry("a", 1), Map.entry("c", 3));
    }
}
