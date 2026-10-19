package io.github.lightrag.storage.mysql;

import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlJsonCodecAttributeTest {
    @Test
    void extractedRecordsCarryAttributesAndOmitEmptyOnes() {
        var entities = List.of(
            new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                "Alice",
                "person",
                "Researcher",
                List.of("Al"),
                Map.of("Age", 33L)
            ),
            new DocumentGraphSnapshotStore.ExtractedEntityRecord(
                "Bob",
                "person",
                "Engineer",
                List.of()
            )
        );
        var relations = List.of(new DocumentGraphSnapshotStore.ExtractedRelationRecord(
            "Alice",
            "Bob",
            "works_with",
            "Collaboration",
            0.8d,
            Map.of("since", 2020L)
        ));

        var entityJson = MySqlJsonCodec.writeExtractedEntityList(entities);
        var relationJson = MySqlJsonCodec.writeExtractedRelationList(relations);

        assertThat(entityJson).contains("\"attributes\":{\"Age\":33}");
        assertThat(entityJson).doesNotContain("\"attributes\":{}");
        assertThat(relationJson).contains("\"attributes\":{\"since\":2020}");
        assertThat(MySqlJsonCodec.readExtractedEntityList(entityJson)).isEqualTo(entities);
        assertThat(MySqlJsonCodec.readExtractedRelationList(relationJson)).isEqualTo(relations);
    }

    @Test
    void legacyJsonWithoutAttributesReadsAsEmpty() {
        var legacyEntities = "[{\"name\":\"Alice\",\"type\":\"person\",\"description\":\"Researcher\",\"aliases\":[\"Al\"]}]";
        var legacyRelations = """
            [{"sourceEntityName":"Alice","targetEntityName":"Bob","keywords":"works_with",
              "description":"Collaboration","weight":0.8}]
            """;

        assertThat(MySqlJsonCodec.readExtractedEntityList(legacyEntities)).singleElement()
            .satisfies(entity -> assertThat(entity.attributes()).isEmpty());
        assertThat(MySqlJsonCodec.readExtractedRelationList(legacyRelations)).singleElement()
            .satisfies(relation -> assertThat(relation.attributes()).isEmpty());
    }
}
