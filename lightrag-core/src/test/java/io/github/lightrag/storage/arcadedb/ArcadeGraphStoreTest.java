package io.github.lightrag.storage.arcadedb;

import io.github.lightrag.storage.GraphStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeGraphStoreTest {
    @Test
    void saveEntityCarriesTheFilePathIntoTheInsert() {
        var client = new RecordingArcadeClient();
        var store = new ArcadeGraphStore(client, "default");

        store.saveEntity(new GraphStore.EntityRecord(
            "alice", "Alice", "person", "Researcher", List.of("Al"),
            List.of("chunk-1"), "/a.md<SEP>/b.md"));

        assertThat(client.commandSql).singleElement().satisfies(sql ->
            assertThat(sql).contains("INSERT INTO Entity").contains("filePath = ?"));
        assertThat(client.commandParams).singleElement().satisfies(params ->
            assertThat(params).contains("/a.md<SEP>/b.md"));
    }

    @Test
    void loadEntityReadsTheStoredFilePathAndDefaultsLegacyRowsToEmpty() {
        var client = new RecordingArcadeClient();
        client.rows = List.of(Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "aliases", "[\"Al\"]",
            "sourceChunkIds", "[\"chunk-1\"]",
            "filePath", "/a.md<SEP>/b.md"
        ));
        var store = new ArcadeGraphStore(client, "default");

        assertThat(store.loadEntity("alice")).get()
            .extracting(GraphStore.EntityRecord::filePath)
            .isEqualTo("/a.md<SEP>/b.md");

        var legacyClient = new RecordingArcadeClient();
        legacyClient.rows = List.of(Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "aliases", "[]",
            "sourceChunkIds", "[]"
        ));
        var legacyStore = new ArcadeGraphStore(legacyClient, "default");

        assertThat(legacyStore.loadEntity("alice")).get()
            .extracting(GraphStore.EntityRecord::filePath)
            .isEqualTo("");
    }

    @Test
    void saveEntityCarriesTheAttributeBagIntoTheInsert() {
        var client = new RecordingArcadeClient();
        var store = new ArcadeGraphStore(client, "default");

        store.saveEntity(new GraphStore.EntityRecord(
            "alice", "Alice", "person", "Researcher", List.of("Al"),
            List.of("chunk-1"), "/a.md", Map.of("age", 42L, "active", true)));

        assertThat(client.commandSql).singleElement().satisfies(sql ->
            assertThat(sql).contains("INSERT INTO Entity").contains("attributes = ?"));
        assertThat(client.commandParams).singleElement().satisfies(params ->
            assertThat(params).contains("{\"active\":true,\"age\":42}"));
    }

    @Test
    void saveRelationCarriesTheAttributeBagIntoTheInsert() {
        var client = new RecordingArcadeClient();
        var store = new ArcadeGraphStore(client, "default");

        store.saveRelation(new GraphStore.RelationRecord(
            "rel-1", "alice", "bob", "knows", "linked", 0.8d, "chunk-1", "/a.md",
            Map.of("since", 2020L)));

        assertThat(client.commandSql).singleElement().satisfies(sql ->
            assertThat(sql).contains("INSERT INTO Relation").contains("attributes = ?"));
        assertThat(client.commandParams).singleElement().satisfies(params ->
            assertThat(params).contains("{\"since\":2020}"));
    }

    @Test
    void emptyAttributesArePersistedAsAnEmptyJsonObject() {
        var client = new RecordingArcadeClient();
        var store = new ArcadeGraphStore(client, "default");

        store.saveEntity(new GraphStore.EntityRecord("alice", "Alice", "person", "", List.of(), List.of()));

        assertThat(client.commandParams).singleElement().satisfies(params -> assertThat(params).contains("{}"));
    }

    @Test
    void loadEntityReadsTheAttributeBagAndDefaultsLegacyRowsToEmpty() {
        var client = new RecordingArcadeClient();
        client.rows = List.of(Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "aliases", "[\"Al\"]",
            "sourceChunkIds", "[\"chunk-1\"]",
            "filePath", "/a.md",
            "attributes", "{\"age\":42,\"tags\":[\"x\",\"y\"]}"
        ));
        var store = new ArcadeGraphStore(client, "default");

        assertThat(store.loadEntity("alice")).get()
            .extracting(GraphStore.EntityRecord::attributes)
            .isEqualTo(Map.of("age", 42L, "tags", List.of("x", "y")));
        assertThat(client.querySql).singleElement().satisfies(sql ->
            assertThat(sql).contains("filePath, attributes FROM Entity"));

        var legacyClient = new RecordingArcadeClient();
        legacyClient.rows = List.of(Map.of(
            "id", "alice",
            "name", "Alice",
            "type", "person",
            "description", "Researcher",
            "aliases", "[]",
            "sourceChunkIds", "[]"
        ));
        var legacyStore = new ArcadeGraphStore(legacyClient, "default");

        assertThat(legacyStore.loadEntity("alice")).get()
            .extracting(GraphStore.EntityRecord::attributes)
            .isEqualTo(Map.of());
    }

    @Test
    void loadRelationReadsTheAttributeBagAndDefaultsLegacyRowsToEmpty() {
        var client = new RecordingArcadeClient();
        client.rows = List.of(Map.of(
            "id", "rel-1",
            "srcId", "alice",
            "tgtId", "bob",
            "keywords", "knows",
            "description", "linked",
            "weight", 0.8d,
            "sourceId", "chunk-1",
            "filePath", "/a.md",
            "attributes", "{\"since\":2020}"
        ));
        var store = new ArcadeGraphStore(client, "default");

        assertThat(store.loadRelation("rel-1")).get()
            .extracting(GraphStore.RelationRecord::attributes)
            .isEqualTo(Map.of("since", 2020L));
        assertThat(client.querySql).singleElement().satisfies(sql ->
            assertThat(sql).contains("filePath, attributes FROM Relation"));

        var legacyClient = new RecordingArcadeClient();
        legacyClient.rows = List.of(Map.of(
            "id", "rel-1",
            "srcId", "alice",
            "tgtId", "bob",
            "keywords", "knows",
            "description", "linked",
            "weight", 0.8d,
            "sourceId", "chunk-1",
            "filePath", "/a.md"
        ));
        var legacyStore = new ArcadeGraphStore(legacyClient, "default");

        assertThat(legacyStore.loadRelation("rel-1")).get()
            .extracting(GraphStore.RelationRecord::attributes)
            .isEqualTo(Map.of());
    }

    private static final class RecordingArcadeClient extends ArcadeDbClient {
        private List<Map<String, Object>> rows = List.of();
        private final List<String> commandSql = new ArrayList<>();
        private final List<List<Object>> commandParams = new ArrayList<>();
        private final List<String> querySql = new ArrayList<>();

        private RecordingArcadeClient() {
            super(ArcadeDbConfig.builder().vectorDimensions(3).initSchema(false).build());
        }

        @Override
        public List<Map<String, Object>> query(String sql, Object... parameters) {
            querySql.add(sql);
            return rows;
        }

        @Override
        public List<Map<String, Object>> command(String language, String command, Object... parameters) {
            commandSql.add(command);
            commandParams.add(List.of(parameters));
            return List.of();
        }
    }
}
