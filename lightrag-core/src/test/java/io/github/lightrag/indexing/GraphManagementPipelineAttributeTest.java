package io.github.lightrag.indexing;

import io.github.lightrag.api.CreateEntityRequest;
import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.api.EditEntityRequest;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.MergeEntitiesRequest;
import io.github.lightrag.api.UpdateRelationRequest;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.lightrag.support.RelationIds.relationId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphManagementPipelineAttributeTest {
    private static final String WORKSPACE = "default";

    @Test
    void createEntityPersistsAndExposesAttributes() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        var attributes = Map.<String, Object>of("age", "30", "city", "Paris");

        var created = rag.createEntity(WORKSPACE, CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Alice description")
            .attributes(attributes)
            .build());

        assertThat(created.attributes()).isEqualTo(attributes);
        assertThat(entity(storage, "Alice").attributes()).isEqualTo(attributes);
    }

    @Test
    void createEntityWithoutAttributesStoresEmptyMap() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);

        var created = rag.createEntity(WORKSPACE, CreateEntityRequest.builder()
            .name("Alice")
            .type("person")
            .description("Alice description")
            .build());

        assertThat(created.attributes()).isEmpty();
        assertThat(entity(storage, "Alice").attributes()).isEmpty();
    }

    @Test
    void createRelationPersistsAndExposesAttributes() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of());
        createEntity(rag, "Bob", Map.of());
        var attributes = Map.<String, Object>of("since", "2020", "kind", "colleague");

        var created = rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .keywords("knows")
            .description("Alice knows Bob")
            .attributes(attributes)
            .build());

        assertThat(created.attributes()).isEqualTo(attributes);
        assertThat(relation(storage, "alice", "bob").attributes()).isEqualTo(attributes);
    }

    @Test
    void editEntityKeepsAttributesWhenRequestAttributesAreNull() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        var attributes = Map.<String, Object>of("age", "30", "city", "Paris");
        createEntity(rag, "Alice", attributes);

        var edited = rag.editEntity(WORKSPACE, new EditEntityRequest(
            "Alice",
            null,
            "researcher",
            null,
            null
        ));

        assertThat(edited.type()).isEqualTo("researcher");
        assertThat(edited.attributes()).isEqualTo(attributes);
        assertThat(entity(storage, "Alice").attributes()).isEqualTo(attributes);
    }

    @Test
    void editEntityClearsAttributesWhenRequestAttributesAreEmpty() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("age", "30"));

        var edited = rag.editEntity(WORKSPACE, EditEntityRequest.builder()
            .entityName("Alice")
            .attributes(Map.of())
            .build());

        assertThat(edited.attributes()).isEmpty();
        assertThat(entity(storage, "Alice").attributes()).isEmpty();
    }

    @Test
    void editEntityReplacesAttributesWhenRequestAttributesAreNonEmpty() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("age", "30", "city", "Paris"));
        var replacement = Map.<String, Object>of("age", "31", "role", "lead");

        var edited = rag.editEntity(WORKSPACE, EditEntityRequest.builder()
            .entityName("Alice")
            .attributes(replacement)
            .build());

        assertThat(edited.attributes()).isEqualTo(replacement);
        assertThat(entity(storage, "Alice").attributes()).isEqualTo(replacement);
    }

    @Test
    void updateRelationKeepsAttributesWhenRequestAttributesAreNull() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of());
        createEntity(rag, "Bob", Map.of());
        var attributes = Map.<String, Object>of("since", "2020", "kind", "colleague");
        createRelation(rag, "Alice", "Bob", attributes);

        var updated = rag.updateRelation(WORKSPACE, UpdateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .description("updated description")
            .build());

        assertThat(updated.description()).isEqualTo("updated description");
        assertThat(updated.attributes()).isEqualTo(attributes);
        assertThat(relation(storage, "alice", "bob").attributes()).isEqualTo(attributes);
    }

    @Test
    void updateRelationClearsAttributesWhenRequestAttributesAreEmpty() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of());
        createEntity(rag, "Bob", Map.of());
        createRelation(rag, "Alice", "Bob", Map.of("since", "2020"));

        var updated = rag.updateRelation(WORKSPACE, UpdateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .attributes(Map.of())
            .build());

        assertThat(updated.attributes()).isEmpty();
        assertThat(relation(storage, "alice", "bob").attributes()).isEmpty();
    }

    @Test
    void updateRelationReplacesAttributesWhenRequestAttributesAreNonEmpty() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of());
        createEntity(rag, "Bob", Map.of());
        createRelation(rag, "Alice", "Bob", Map.of("since", "2020", "kind", "colleague"));
        var replacement = Map.<String, Object>of("since", "2021", "scope", "project");

        var updated = rag.updateRelation(WORKSPACE, UpdateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .attributes(replacement)
            .build());

        assertThat(updated.attributes()).isEqualTo(replacement);
        assertThat(relation(storage, "alice", "bob").attributes()).isEqualTo(replacement);
    }

    @Test
    void mergeEntitiesMergesSourceAttributesWithTargetWinningAndPersistsResult() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("shared", "target", "targetonly", "t"));
        createEntity(rag, "Bob", Map.of("shared", "bob", "bobonly", "b"));
        createEntity(rag, "Carol", Map.of("shared", "carol", "carolonly", "c"));

        var merged = rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob", "Carol"))
            .targetEntityName("Alice")
            .build());

        var expected = Map.<String, Object>of(
            "shared", "target",
            "targetonly", "t",
            "bobonly", "b",
            "carolonly", "c"
        );
        assertThat(merged.attributes()).isEqualTo(expected);
        assertThat(storage.graphStore().allEntities()).singleElement()
            .satisfies(entity -> assertThat(entity.attributes()).isEqualTo(expected));
    }

    @Test
    void mergeEntitiesSourceOrderDeterminesSourcePrecedence() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("targetonly", "t"));
        createEntity(rag, "Bob", Map.of("shared", "bob"));
        createEntity(rag, "Carol", Map.of("shared", "carol"));

        var merged = rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Carol", "Bob"))
            .targetEntityName("Alice")
            .build());

        assertThat(merged.attributes()).isEqualTo(Map.of("targetonly", "t", "shared", "bob"));
    }

    @Test
    void mergeEntitiesTargetAttributesReplaceTargetLayerBeforeSourceMerge() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("shared", "target", "targetonly", "t"));
        createEntity(rag, "Bob", Map.of("shared", "bob", "bobonly", "b"));
        createEntity(rag, "Carol", Map.of("shared", "carol", "carolonly", "c"));
        var targetAttributes = Map.<String, Object>of("shared", "explicit", "explicitonly", "e");

        var merged = rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob", "Carol"))
            .targetEntityName("Alice")
            .targetAttributes(targetAttributes)
            .build());

        var expected = Map.<String, Object>of(
            "shared", "explicit",
            "explicitonly", "e",
            "bobonly", "b",
            "carolonly", "c"
        );
        assertThat(merged.attributes()).isEqualTo(expected);
        assertThat(entity(storage, "Alice").attributes()).isEqualTo(expected);
    }

    @Test
    void mergeEntitiesEmptyTargetAttributesClearTargetLayerButKeepSourceAttributes() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of("shared", "target", "targetonly", "t"));
        createEntity(rag, "Bob", Map.of("shared", "bob", "bobonly", "b"));
        createEntity(rag, "Carol", Map.of("shared", "carol", "carolonly", "c"));

        var merged = rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob", "Carol"))
            .targetEntityName("Alice")
            .targetAttributes(Map.of())
            .build());

        var expected = Map.<String, Object>of(
            "shared", "carol",
            "bobonly", "b",
            "carolonly", "c"
        );
        assertThat(merged.attributes()).isEqualTo(expected);
        assertThat(entity(storage, "Alice").attributes()).isEqualTo(expected);
    }

    @Test
    void mergeEntitiesKeepsAndMergesRelationAttributes() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        createEntity(rag, "Alice", Map.of());
        createEntity(rag, "Bob", Map.of());
        createEntity(rag, "Carol", Map.of());
        createRelation(rag, "Alice", "Carol", Map.of("shared", "alice", "aliceonly", "a"));
        createRelation(rag, "Bob", "Carol", Map.of("shared", "bob", "bobonly", "b"));

        rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Alice")
            .build());

        assertThat(storage.graphStore().allRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.id()).isEqualTo(relationId("alice", "carol"));
            assertThat(relation.attributes()).isEqualTo(Map.of(
                "shared", "bob",
                "aliceonly", "a",
                "bobonly", "b"
            ));
        });
    }

    @Test
    void mergeEntitiesRejectsMergedAttributeBagsAboveTheLimitWithoutPersisting() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        var targetAttributes = numberedAttributes("target", 64);
        var sourceAttributes = numberedAttributes("source", 64);
        createEntity(rag, "Alice", targetAttributes);
        createEntity(rag, "Bob", sourceAttributes);

        assertThatThrownBy(() -> rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Alice")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("64 keys: 128");

        assertThat(entity(storage, "Alice").attributes()).isEqualTo(targetAttributes);
        assertThat(entity(storage, "Bob").attributes()).isEqualTo(sourceAttributes);
        assertThat(storage.graphStore().allEntities()).hasSize(2);
    }

    @Test
    void renamePreservesEntityAndMigratedRelationAttributes() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);
        var entityAttributes = Map.<String, Object>of("age", "30", "city", "Paris");
        var relationAttributes = Map.<String, Object>of("since", "2020", "kind", "colleague");
        createEntity(rag, "Alice", entityAttributes);
        createEntity(rag, "Bob", Map.of());
        createRelation(rag, "Alice", "Bob", relationAttributes);

        var renamed = rag.editEntity(WORKSPACE, new EditEntityRequest(
            "Alice",
            "Alicia",
            null,
            null,
            null
        ));

        assertThat(renamed.name()).isEqualTo("Alicia");
        assertThat(renamed.attributes()).isEqualTo(entityAttributes);
        assertThat(entity(storage, "Alicia").attributes()).isEqualTo(entityAttributes);
        assertThat(storage.graphStore().allEntities()).noneMatch(entity -> entity.name().equals("Alice"));
        assertThat(storage.graphStore().allRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.id()).isEqualTo(relationId("alicia", "bob"));
            assertThat(relation.attributes()).isEqualTo(relationAttributes);
        });
    }

    private static Map<String, Object> numberedAttributes(String prefix, int count) {
        var attributes = new LinkedHashMap<String, Object>();
        for (int index = 0; index < count; index++) {
            attributes.put(prefix + index, "value-" + index);
        }
        return attributes;
    }

    private static void createEntity(LightRag rag, String name, Map<String, Object> attributes) {
        rag.createEntity(WORKSPACE, CreateEntityRequest.builder()
            .name(name)
            .type("person")
            .description(name + " description")
            .attributes(attributes)
            .build());
    }

    private static void createRelation(
        LightRag rag,
        String sourceEntityName,
        String targetEntityName,
        Map<String, Object> attributes
    ) {
        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName(sourceEntityName)
            .targetEntityName(targetEntityName)
            .keywords("knows")
            .description(sourceEntityName + " knows " + targetEntityName)
            .sourceId("c1")
            .attributes(attributes)
            .build());
    }

    private static GraphStore.EntityRecord entity(InMemoryStorageProvider storage, String name) {
        return storage.graphStore().allEntities().stream()
            .filter(entity -> entity.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("entity not found: " + name));
    }

    private static GraphStore.RelationRecord relation(
        InMemoryStorageProvider storage,
        String sourceEntityId,
        String targetEntityId
    ) {
        var id = relationId(sourceEntityId, targetEntityId);
        return storage.graphStore().allRelations().stream()
            .filter(relation -> relation.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new AssertionError("relation not found: " + id));
    }

    private static LightRag newRag(InMemoryStorageProvider storage) {
        return LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return "{}";
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
        }
    }
}
