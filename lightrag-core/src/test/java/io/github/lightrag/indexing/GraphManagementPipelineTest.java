package io.github.lightrag.indexing;

import io.github.lightrag.api.CreateEntityRequest;
import io.github.lightrag.api.CreateRelationRequest;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.api.MergeEntitiesRequest;
import io.github.lightrag.api.UpdateRelationRequest;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.lightrag.support.RelationIds.relationId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphManagementPipelineTest {
    private static final String WORKSPACE = "default";

    @Test
    void relationsRedirectedOntoOneEndpointTakeTheEvidenceFloor() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);

        createEntity(rag, "Alice");
        createEntity(rag, "Bob");
        createEntity(rag, "Carol");
        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Carol")
            .keywords("reviews")
            .description("Alice reviews Carol")
            .weight(1.0d)
            .sourceId("c1")
            .build());
        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Bob")
            .targetEntityName("Carol")
            .keywords("reviews")
            .description("Bob reviews Carol")
            .weight(1.0d)
            .sourceId("c2")
            .build());

        rag.mergeEntities(WORKSPACE, MergeEntitiesRequest.builder()
            .sourceEntityNames(List.of("Bob"))
            .targetEntityName("Alice")
            .build());

        assertThat(storage.graphStore().allRelations()).singleElement().satisfies(relation -> {
            assertThat(relation.id()).isEqualTo(relationId("alice", "carol"));
            assertThat(relation.sourceChunkIds()).containsExactly("c1", "c2");
            assertThat(relation.weight()).isEqualTo(2.0d);
        });
    }

    @Test
    void manualWeightBelowTheEvidenceCountIsRejected() {
        var storage = InMemoryStorageProvider.create();
        var rag = newRag(storage);

        createEntity(rag, "Alice");
        createEntity(rag, "Bob");
        assertThatThrownBy(() -> rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .keywords("works_with")
            .description("with Bob")
            .weight(0.5d)
            .sourceId("c1<SEP>c2")
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence count 2");

        rag.createRelation(WORKSPACE, CreateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .keywords("works_with")
            .description("with Bob")
            .weight(2.0d)
            .sourceId("c1<SEP>c2")
            .build());
        assertThatThrownBy(() -> rag.updateRelation(WORKSPACE, UpdateRelationRequest.builder()
            .sourceEntityName("Alice")
            .targetEntityName("Bob")
            .weight(0.5d)
            .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evidence count 2");
    }

    private static LightRag newRag(InMemoryStorageProvider storage) {
        return LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
    }

    private static void createEntity(LightRag rag, String name) {
        rag.createEntity(WORKSPACE, CreateEntityRequest.builder()
            .name(name)
            .type("person")
            .description(name + " description")
            .build());
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
