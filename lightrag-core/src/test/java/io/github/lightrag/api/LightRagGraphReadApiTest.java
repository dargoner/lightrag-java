package io.github.lightrag.api;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.InMemoryStorageProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LightRagGraphReadApiTest {
    @Test
    void exposesLabelsSearchAndBoundedTraversalForAWorkspace() {
        var rag = newLightRag(null);
        createEntity(rag, "Alpha");
        createEntity(rag, "Beta");
        createEntity(rag, "Gamma");
        createEntity(rag, "Delta");
        createRelation(rag, "Alpha", "Beta");
        createRelation(rag, "Alpha", "Gamma");
        createRelation(rag, "Alpha", "Delta");
        createRelation(rag, "Beta", "Gamma");

        assertThat(rag.getGraphLabels("default"))
            .containsExactly("alpha", "beta", "delta", "gamma");
        assertThat(rag.searchGraphLabels("default", "BET", 10)).containsExactly("beta");
        assertThat(rag.searchGraphLabels("default", "a", 2)).containsExactly("alpha", "beta");

        var visible = rag.getKnowledgeGraph("default", "*", 3, 2);

        assertThat(visible.truncated()).isTrue();
        assertThat(visible.nodes()).extracting(GraphEntity::id).containsExactly("alpha", "beta");

        var neighborhood = rag.getKnowledgeGraph("default", "alpha", 1, 50);

        assertThat(neighborhood.truncated()).isTrue();
        assertThat(neighborhood.nodes()).extracting(GraphEntity::id)
            .containsExactly("alpha", "beta", "gamma", "delta");
        assertThat(neighborhood.edges()).hasSize(4);

        var unknown = rag.getKnowledgeGraph("default", "missing", 1, 50);

        assertThat(unknown.nodes()).isEmpty();
        assertThat(unknown.edges()).isEmpty();
        assertThat(unknown.truncated()).isFalse();
    }

    @Test
    void clampsTheRequestedNodeBudgetToTheConfiguredMaximum() {
        var rag = newLightRag(2);
        createEntity(rag, "Alpha");
        createEntity(rag, "Beta");
        createEntity(rag, "Gamma");

        var view = rag.getKnowledgeGraph("default", "*", 3, 50);

        assertThat(view.truncated()).isTrue();
        assertThat(view.nodes()).hasSize(2);
    }

    private static LightRag newLightRag(Integer maxGraphNodes) {
        var builder = LightRag.builder()
            .chatModel(new NoOpChatModel())
            .embeddingModel(new FixedEmbeddingModel())
            .storage(new InMemoryStorageProvider())
            .automaticQueryKeywordExtraction(false);
        if (maxGraphNodes != null) {
            builder.maxGraphNodes(maxGraphNodes);
        }
        return builder.build();
    }

    private static void createEntity(LightRag rag, String name) {
        rag.createEntity("default", CreateEntityRequest.builder()
            .name(name)
            .type("Concept")
            .build());
    }

    private static void createRelation(LightRag rag, String sourceName, String targetName) {
        rag.createRelation("default", CreateRelationRequest.builder()
            .sourceEntityName(sourceName)
            .targetEntityName(targetName)
            .keywords("relates")
            .build());
    }

    private static final class NoOpChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {
                  "entities": [],
                  "relations": []
                }
                """;
        }
    }

    private static final class FixedEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of(1.0d, 0.0d)).toList();
        }
    }
}
