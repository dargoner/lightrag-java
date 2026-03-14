package io.github.lightragjava.api;

import io.github.lightragjava.model.ChatModel;
import io.github.lightragjava.model.EmbeddingModel;
import io.github.lightragjava.storage.ChunkStore;
import io.github.lightragjava.storage.DocumentStore;
import io.github.lightragjava.storage.GraphStore;
import io.github.lightragjava.storage.SnapshotStore;
import io.github.lightragjava.storage.StorageProvider;
import io.github.lightragjava.storage.VectorStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LightRagBuilderTest {

    @Test
    void buildsWithRequiredDependencies() {
        var rag = LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(new FakeStorageProvider())
            .build();

        assertThat(rag).isNotNull();
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
    void rejectsMissingStorageProvider() {
        assertThatThrownBy(() -> LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .build())
            .isInstanceOf(IllegalStateException.class);
    }

    private static final class FakeChatModel implements ChatModel {
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
    }

    private static final class FakeStorageProvider implements StorageProvider {
        private final DocumentStore documentStore = new FakeDocumentStore();
        private final ChunkStore chunkStore = new FakeChunkStore();
        private final GraphStore graphStore = new FakeGraphStore();
        private final VectorStore vectorStore = new FakeVectorStore();
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
        public SnapshotStore snapshotStore() {
            return snapshotStore;
        }
    }

    private static final class FakeDocumentStore implements DocumentStore {
    }

    private static final class FakeChunkStore implements ChunkStore {
    }

    private static final class FakeGraphStore implements GraphStore {
    }

    private static final class FakeVectorStore implements VectorStore {
    }

    private static final class FakeSnapshotStore implements SnapshotStore {
    }
}
