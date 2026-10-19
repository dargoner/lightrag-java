package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.DocumentGraphSnapshotStore.ExtractedEntityRecord;
import io.github.lightrag.storage.DocumentGraphSnapshotStore.ExtractedRelationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON shape of the attribute bag in the ArcadeDB snapshot codec: canonical graph-column form,
 * record round-trip, and legacy payloads without the field.
 */
class ArcadeJsonCodecTest {

    @Test
    void graphAttributeColumnRoundTripsCanonicalJson() {
        var encoded = ArcadeJsonCodec.writeAttributes(Map.of("tags", List.of("x", "y"), "age", 42L));

        assertThat(encoded).isEqualTo("{\"age\":42,\"tags\":[\"x\",\"y\"]}");
        assertThat(ArcadeJsonCodec.readAttributes(encoded))
            .isEqualTo(Map.of("age", 42L, "tags", List.of("x", "y")));
    }

    @Test
    void graphAttributeColumnDefaultsMissingAndMalformedValuesToEmpty() {
        assertThat(ArcadeJsonCodec.readAttributes(null)).isEmpty();
        assertThat(ArcadeJsonCodec.readAttributes("")).isEmpty();
        assertThat(ArcadeJsonCodec.readAttributes("   ")).isEmpty();
        assertThat(ArcadeJsonCodec.readAttributes("{not json")).isEmpty();
        assertThat(ArcadeJsonCodec.readAttributes("{}")).isEmpty();
    }

    @Test
    void graphAttributeColumnAcceptsAnAlreadyConvertedMap() {
        assertThat(ArcadeJsonCodec.readAttributes(Map.of("age", 42L)))
            .isEqualTo(Map.of("age", 42L));
    }

    @Test
    void extractedEntityRecordsCarryAttributesThroughTheSnapshotCodec() {
        var records = List.of(new ExtractedEntityRecord(
            "Alice", "person", "researcher", List.of("Al"), Map.of("age", 42L, "nickname", "Al")));

        var json = ArcadeJsonCodec.writeExtractedEntityRecordList(records);

        assertThat(json).contains("\"attributes\":{\"age\":42,\"nickname\":\"Al\"}");
        assertThat(ArcadeJsonCodec.readExtractedEntityRecordList(json))
            .singleElement()
            .satisfies(record -> {
                assertThat(record.name()).isEqualTo("Alice");
                assertThat(record.aliases()).containsExactly("Al");
                // Jackson materialises JSON integers as Integer, not the Long used on write.
                assertThat(((Number) record.attributes().get("age")).longValue()).isEqualTo(42L);
                assertThat(record.attributes().get("nickname")).isEqualTo("Al");
            });
    }

    @Test
    void extractedRelationRecordsCarryAttributesThroughTheSnapshotCodec() {
        var records = List.of(new ExtractedRelationRecord(
            "Alice", "Bob", "knows", "linked", 0.8d, Map.of("since", 2020L)));

        var json = ArcadeJsonCodec.writeExtractedRelationRecordList(records);

        assertThat(json).contains("\"attributes\":{\"since\":2020}");
        assertThat(ArcadeJsonCodec.readExtractedRelationRecordList(json))
            .singleElement()
            .satisfies(record -> {
                assertThat(record.sourceEntityName()).isEqualTo("Alice");
                assertThat(record.targetEntityName()).isEqualTo("Bob");
                assertThat(record.weight()).isEqualTo(0.8d);
                // Jackson materialises JSON integers as Integer, not the Long used on write.
                assertThat(((Number) record.attributes().get("since")).longValue()).isEqualTo(2020L);
            });
    }

    @Test
    void emptyAttributesAreOmittedFromTheSnapshotJson() {
        var entityJson = ArcadeJsonCodec.writeExtractedEntityRecordList(List.of(
            new ExtractedEntityRecord("Alice", "person", "", List.of())));
        var relationJson = ArcadeJsonCodec.writeExtractedRelationRecordList(List.of(
            new ExtractedRelationRecord("Alice", "Bob", "knows", "", 0.8d)));

        assertThat(entityJson).doesNotContain("attributes");
        assertThat(relationJson).doesNotContain("attributes");
    }

    @Test
    void legacySnapshotJsonWithoutAttributesReadsAsEmpty() {
        // Phase 1 payload shape: the attribute component did not exist.
        var legacyEntities = "[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"\",\"aliases\":[]}]";
        var legacyRelations =
            "[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\",\"keywords\":\"knows\","
                + "\"description\":\"\",\"weight\":0.8}]";

        assertThat(ArcadeJsonCodec.readExtractedEntityRecordList(legacyEntities))
            .singleElement()
            .satisfies(record -> assertThat(record.attributes()).isEmpty());
        assertThat(ArcadeJsonCodec.readExtractedRelationRecordList(legacyRelations))
            .singleElement()
            .satisfies(record -> assertThat(record.attributes()).isEmpty());
    }

    @Test
    void snapshotJsonWithNullAttributesReadsAsEmpty() {
        var entities = "[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"\",\"aliases\":[],"
            + "\"attributes\":null}]";

        assertThat(ArcadeJsonCodec.readExtractedEntityRecordList(entities))
            .singleElement()
            .satisfies(record -> assertThat(record.attributes()).isEmpty());
    }
}
