package io.github.lightrag.storage.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightrag.storage.DocumentGraphSnapshotStore.ExtractedEntityRecord;
import io.github.lightrag.storage.DocumentGraphSnapshotStore.ExtractedRelationRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the chunk-snapshot JSON shape for materialized attributes: non-empty bags serialize under
 * {@code attributes}, empty bags are omitted so a new empty record is byte-identical to a legacy
 * snapshot, and legacy JSON without the field reads back as an empty map.
 */
class JdbcJsonCodecAttributeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String LEGACY_ENTITY_JSON =
        "[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Researcher\",\"aliases\":[\"A\"]}]";
    private static final String LEGACY_RELATION_JSON =
        "[{\"sourceEntityName\":\"Alice\",\"targetEntityName\":\"Bob\","
            + "\"keywords\":\"knows\",\"description\":\"Alice knows Bob\",\"weight\":0.9}]";

    @Test
    void emptyAttributesAreOmittedFromTheSerializedEntityShape() throws Exception {
        var encoded = JdbcJsonCodec.writeExtractedEntityRecordList(List.of(
            new ExtractedEntityRecord("Alice", "person", "Researcher", List.of("A"))
        ));

        assertThat(encoded).isEqualTo(LEGACY_ENTITY_JSON);
        JsonNode record = OBJECT_MAPPER.readTree(encoded).get(0);
        assertThat(record.has("attributes")).isFalse();
    }

    @Test
    void emptyAttributesAreOmittedFromTheSerializedRelationShape() throws Exception {
        var encoded = JdbcJsonCodec.writeExtractedRelationRecordList(List.of(
            new ExtractedRelationRecord("Alice", "Bob", "knows", "Alice knows Bob", 0.9d)
        ));

        assertThat(encoded).isEqualTo(LEGACY_RELATION_JSON);
        JsonNode record = OBJECT_MAPPER.readTree(encoded).get(0);
        assertThat(record.has("attributes")).isFalse();
    }

    @Test
    void entityAttributesRoundTripThroughTheSnapshotShape() throws Exception {
        var entity = new ExtractedEntityRecord(
            "Alice",
            "person",
            "Researcher",
            List.of("A"),
            Map.of("age", 30L, "tags", List.of("lead", "author"))
        );

        var encoded = JdbcJsonCodec.writeExtractedEntityRecordList(List.of(entity));
        JsonNode attributes = OBJECT_MAPPER.readTree(encoded).get(0).get("attributes");
        assertThat(attributes).isNotNull();
        assertThat(attributes.get("age").asInt()).isEqualTo(30);
        assertThat(attributes.get("tags")).hasSize(2);

        assertThat(JdbcJsonCodec.readExtractedEntityRecordList(encoded)).containsExactly(entity);
    }

    @Test
    void relationAttributesRoundTripThroughTheSnapshotShape() throws Exception {
        var relation = new ExtractedRelationRecord(
            "Alice",
            "Bob",
            "knows",
            "Alice knows Bob",
            0.9d,
            Map.of("since", 1999L)
        );

        var encoded = JdbcJsonCodec.writeExtractedRelationRecordList(List.of(relation));
        JsonNode attributes = OBJECT_MAPPER.readTree(encoded).get(0).get("attributes");
        assertThat(attributes).isNotNull();
        assertThat(attributes.get("since").asInt()).isEqualTo(1999);

        assertThat(JdbcJsonCodec.readExtractedRelationRecordList(encoded)).containsExactly(relation);
    }

    @Test
    void legacyEntityJsonWithoutAttributesReadsAsEmptyMap() {
        var decoded = JdbcJsonCodec.readExtractedEntityRecordList(LEGACY_ENTITY_JSON);

        assertThat(decoded).containsExactly(
            new ExtractedEntityRecord("Alice", "person", "Researcher", List.of("A"))
        );
        assertThat(decoded.get(0).attributes()).isEmpty();
    }

    @Test
    void legacyRelationJsonWithoutAttributesReadsAsEmptyMap() {
        var decoded = JdbcJsonCodec.readExtractedRelationRecordList(LEGACY_RELATION_JSON);

        assertThat(decoded).containsExactly(
            new ExtractedRelationRecord("Alice", "Bob", "knows", "Alice knows Bob", 0.9d)
        );
        assertThat(decoded.get(0).attributes()).isEmpty();
    }

    @Test
    void nullAttributesFieldReadsAsEmptyMap() {
        var decoded = JdbcJsonCodec.readExtractedEntityRecordList(
            "[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Researcher\","
                + "\"aliases\":[],\"attributes\":null}]"
        );

        assertThat(decoded.get(0).attributes()).isEmpty();
    }

    @Test
    void mixedLegacyAndAttributeBearingRecordsKeepTheirOwnAttributes() {
        var encoded = JdbcJsonCodec.writeExtractedRelationRecordList(List.of(
            new ExtractedRelationRecord("Alice", "Bob", "knows", "", 0.5d),
            new ExtractedRelationRecord("Bob", "Carol", "knows", "", 0.7d, Map.of("since", 2001))
        ));

        var decoded = JdbcJsonCodec.readExtractedRelationRecordList(encoded);

        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).attributes()).isEmpty();
        assertThat(decoded.get(1).attributes()).containsEntry("since", 2001L);
    }
}
