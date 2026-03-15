package io.github.lightragjava.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.model.RerankModel;
import io.github.lightragjava.storage.AtomicStorageProvider;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.DocumentStatusStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.VectorStore;
import io.github.lightragjava.storage.memory.InMemoryDocumentStatusStore;
import io.github.lightragjava.types.Document;
import io.github.lightragjava.types.ExtractedRelation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagBuilderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    @Test
    void buildsWithRequiredDependencies() {
        var chatModel = new FakeChatModel();
        var embeddingModel = new FakeEmbeddingModel();
        var storageProvider = new FakeStorageProvider();
        var rag = LightRag.builder()
            .chatModel(chatModel)
            .embeddingModel(embeddingModel)
            .storage(storageProvider)
            .build();

        assertThat(rag).isNotNull();
        assertThat(rag.config().chatModel()).isSameAs(chatModel);
        assertThat(rag.config().embeddingModel()).isSameAs(embeddingModel);
        assertThat(rag.config().storageProvider()).isSameAs(storageProvider);
    }

    @Test
    void retainsSnapshotPathAfterBuild() {
        var snapshotPath = Path.of("snapshots", "repository.json");

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .loadFromSnapshot(snapshotPath)
            .build();

        assertThat(rag.config().snapshotPath()).isEqualTo(snapshotPath);
    }

    @Test
    void buildsWithOptionalRerankModel() {
        var rerankModel = new FakeRerankModel();

        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .rerankModel(rerankModel)
            .build();

        assertThat(rag.config().rerankModel()).isSameAs(rerankModel);
    }

    @Test
    void rejectsMissingChatModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingEmbeddingModel() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .storage(new FakeStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new MalformedStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("documentStore");
    }

    @Test
    void rejectsNullChatModel() {
        assertThatThrownBy(() -> LightRag.builder().chatModel(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("chatModel");
    }

    @Test
    void rejectsNullEmbeddingModel() {
        assertThatThrownBy(() -> LightRag.builder().embeddingModel(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("embeddingModel");
    }

    @Test
    void rejectsNullStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder().storage(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("storageProvider");
    }

    @Test
    void rejectsNullSnapshotPath() {
        assertThatThrownBy(() -> LightRag.builder().loadFromSnapshot(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("path");
    }

    @Test
    void rejectsMissingStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonAtomicStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new PlainStorageProvider())
            .build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("AtomicStorageProvider");
    }

    @Test
    void queryRequestDefaultsToMixMode() {
        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .build();

        assertThat(request.query()).isEqualTo("Where is the evidence?");
        assertThat(request.mode()).isEqualTo(QueryMode.MIX);
        assertThat(request.topK()).isEqualTo(QueryRequest.DEFAULT_TOP_K);
        assertThat(request.chunkTopK()).isEqualTo(QueryRequest.DEFAULT_CHUNK_TOP_K);
        assertThat(request.responseType()).isEqualTo(QueryRequest.DEFAULT_RESPONSE_TYPE);
        assertThat(request.enableRerank()).isTrue();
        assertThat(request.userPrompt()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
    }

    @Test
    void queryRequestLegacyConstructorDefaultsPromptControls() {
        var request = new QueryRequest(
            "Where is the evidence?",
            QueryMode.LOCAL,
            5,
            7,
            "text",
            false
        );

        assertThat(request.query()).isEqualTo("Where is the evidence?");
        assertThat(request.mode()).isEqualTo(QueryMode.LOCAL);
        assertThat(request.topK()).isEqualTo(5);
        assertThat(request.chunkTopK()).isEqualTo(7);
        assertThat(request.responseType()).isEqualTo("text");
        assertThat(request.enableRerank()).isFalse();
        assertThat(request.userPrompt()).isEmpty();
        assertThat(request.conversationHistory()).isEmpty();
    }

    @Test
    void queryRequestCopiesConversationHistory() {
        var history = new ArrayList<ChatModel.ChatRequest.ConversationMessage>();
        history.add(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));

        var request = QueryRequest.builder()
            .query("Where is the evidence?")
            .conversationHistory(history)
            .build();
        history.clear();

        assertThat(request.conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
        assertThatThrownBy(() -> request.conversationHistory().add(
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        ))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void chatRequestCopiesConversationHistory() {
        var history = new ArrayList<ChatModel.ChatRequest.ConversationMessage>();
        history.add(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));

        var request = new ChatModel.ChatRequest("System prompt", "Current user prompt", history);
        history.clear();

        assertThat(request.conversationHistory())
            .containsExactly(new ChatModel.ChatRequest.ConversationMessage("user", "Earlier question"));
        assertThatThrownBy(() -> request.conversationHistory().add(
            new ChatModel.ChatRequest.ConversationMessage("assistant", "Earlier answer")
        ))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void queryModeExposesNaiveAndQueryRequestAcceptsIt() {
        var request = QueryRequest.builder()
            .query("Find the direct chunk")
            .mode(QueryMode.NAIVE)
            .build();

        assertThat(QueryMode.valueOf("NAIVE")).isEqualTo(QueryMode.NAIVE);
        assertThat(request.mode()).isEqualTo(QueryMode.NAIVE);
    }

    @Test
    void queryResultCopiesContexts() {
        var contexts = new ArrayList<QueryResult.Context>();
        contexts.add(new QueryResult.Context("chunk-1", "supporting context"));

        var result = new QueryResult("answer", contexts);
        contexts.clear();

        assertThat(result.answer()).isEqualTo("answer");
        assertThat(result.contexts()).containsExactly(new QueryResult.Context("chunk-1", "supporting context"));
        assertThatThrownBy(() -> result.contexts().add(new QueryResult.Context("chunk-2", "more context")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void documentRequiresNonBlankId() {
        assertThatThrownBy(() -> new Document(" ", "Title", "Body", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractedRelationRequiresSourceTargetAndType() {
        assertThatThrownBy(() -> new ExtractedRelation("Alice", "", "works_with", "", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractedRelationDefaultsMissingWeightWhenDeserialized() throws Exception {
        var relation = OBJECT_MAPPER.readValue("""
            {
              "sourceEntityName": "Alice",
              "targetEntityName": "Bob",
              "type": "works_with",
              "description": "collaboration"
            }
            """, ExtractedRelation.class);

        assertThat(relation.weight()).isEqualTo(1.0d);
    }

    @Test
    void extractedRelationPreservesExplicitZeroWeightWhenDeserialized() throws Exception {
        var relation = OBJECT_MAPPER.readValue("""
            {
              "sourceEntityName": "Alice",
              "targetEntityName": "Bob",
              "type": "works_with",
              "description": "collaboration",
              "weight": 0.0
            }
            """, ExtractedRelation.class);

        assertThat(relation.weight()).isEqualTo(0.0d);
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return request.userPrompt();
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream()
                .map(text -> List.of((double) text.length()))
                .toList();
        }
    }

    private static final class FakeRerankModel implements RerankModel {
        @Override
        public List<RerankResult> rerank(RerankRequest request) {
            return request.candidates().stream()
                .map(candidate -> new RerankResult(candidate.id(), 1.0d))
                .toList();
        }
    }

    private static class FakeStorageProvider implements AtomicStorageProvider {
        private final DocumentStore documentStore = new FakeDocumentStore();
        private final ChunkStore chunkStore = new FakeChunkStore();
        private final GraphStore graphStore = new FakeGraphStore();
        private final VectorStore vectorStore = new FakeVectorStore();
        private final DocumentStatusStore documentStatusStore = new InMemoryDocumentStatusStore();
        private final SnapshotStore snapshotStore = new FakeSnapshotStore();

        @Override
        public DocumentStore documentStore() {
            return documentStore;
        }

        @Override
        public ChunkStore chunkStore() {
            return chunkStore;
        }

        @Override
        public GraphStore graphStore() {
            return graphStore;
        }

        @Override
        public VectorStore vectorStore() {
            return vectorStore;
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return documentStatusStore;
        }

        @Override
        public SnapshotStore snapshotStore() {
            return snapshotStore;
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            return operation.execute(new AtomicStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return documentStore;
                }

                @Override
                public ChunkStore chunkStore() {
                    return chunkStore;
                }

                @Override
                public GraphStore graphStore() {
                    return graphStore;
                }

                @Override
                public VectorStore vectorStore() {
                    return vectorStore;
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return documentStatusStore;
                }
            });
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
        }
    }

    private static final class PlainStorageProvider implements StorageProvider {
        private final FakeStorageProvider delegate = new FakeStorageProvider();

        @Override
        public DocumentStore documentStore() {
            return delegate.documentStore();
        }

        @Override
        public ChunkStore chunkStore() {
            return delegate.chunkStore();
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public VectorStore vectorStore() {
            return delegate.vectorStore();
        }

        @Override
        public DocumentStatusStore documentStatusStore() {
            return delegate.documentStatusStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }
    }

    private static final class MalformedStorageProvider extends FakeStorageProvider {
        @Override
        public DocumentStore documentStore() {
            return null;
        }
    }

    private static final class FakeDocumentStore implements DocumentStore {
        @Override
        public void save(DocumentRecord document) {
        }

        @Override
        public Optional<DocumentRecord> load(String documentId) {
            return Optional.empty();
        }

        @Override
        public List<DocumentRecord> list() {
            return List.of();
        }

        @Override
        public boolean contains(String documentId) {
            return false;
        }
    }

    private static final class FakeChunkStore implements ChunkStore {
        @Override
        public void save(ChunkRecord chunk) {
        }

        @Override
        public Optional<ChunkRecord> load(String chunkId) {
            return Optional.empty();
        }

        @Override
        public List<ChunkRecord> list() {
            return List.of();
        }

        @Override
        public List<ChunkRecord> listByDocument(String documentId) {
            return List.of();
        }
    }

    private static final class FakeGraphStore implements GraphStore {
        @Override
        public void saveEntity(EntityRecord entity) {
        }

        @Override
        public void saveRelation(RelationRecord relation) {
        }

        @Override
        public Optional<EntityRecord> loadEntity(String entityId) {
            return Optional.empty();
        }

        @Override
        public Optional<RelationRecord> loadRelation(String relationId) {
            return Optional.empty();
        }

        @Override
        public List<EntityRecord> allEntities() {
            return List.of();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return List.of();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return List.of();
        }
    }

    private static final class FakeVectorStore implements VectorStore {
        @Override
        public void saveAll(String namespace, List<VectorRecord> vectors) {
        }

        @Override
        public List<VectorMatch> search(String namespace, List<Double> queryVector, int topK) {
            return List.of();
        }

        @Override
        public List<VectorRecord> list(String namespace) {
            return List.of();
        }
    }

    private static final class FakeSnapshotStore implements SnapshotStore {
        @Override
        public void save(Path path, Snapshot snapshot) {
        }

        @Override
        public Snapshot load(Path path) {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), Map.of());
        }

        @Override
        public List<Path> list() {
            return List.of();
        }
    }
}
