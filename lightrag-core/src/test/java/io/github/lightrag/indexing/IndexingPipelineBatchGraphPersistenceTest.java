package io.github.lightrag.indexing;

import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.AtomicStorageProvider;
import io.github.lightrag.storage.ChunkStore;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.DocumentStatusStore;
import io.github.lightrag.storage.DocumentStore;
import io.github.lightrag.storage.GraphStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.storage.SnapshotStore;
import io.github.lightrag.storage.TaskStageStore;
import io.github.lightrag.storage.TaskStore;
import io.github.lightrag.storage.VectorStore;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.support.RelationIds;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingPipelineBatchGraphPersistenceTest {
    @Test
    void ingestPersistsGraphThroughBatchApis() {
        var storage = new BatchOnlyAtomicStorageProvider();
        var pipeline = new IndexingPipeline(
            new FakeChatModel(),
            new FakeEmbeddingModel(),
            storage,
            null
        );

        pipeline.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));

        assertThat(storage.graphRecorder.loadedEntityIdBatches)
            .containsExactly(List.of(entityKey("Alice"), entityKey("Bob")));
        assertThat(storage.graphRecorder.loadedRelationIdBatches)
            .containsExactly(List.of(relationKey("Alice", "works_with", "Bob")));
        assertThat(storage.graphRecorder.savedEntityBatches).singleElement().satisfies(batch ->
            assertThat(batch).extracting(GraphStore.EntityRecord::id)
                .containsExactly(entityKey("Alice"), entityKey("Bob"))
        );
        assertThat(storage.graphRecorder.savedRelationBatches).singleElement().satisfies(batch ->
            assertThat(batch).extracting(GraphStore.RelationRecord::id)
                .containsExactly(relationKey("Alice", "works_with", "Bob"))
        );
        assertThat(storage.graphStore().allEntities()).hasSize(2);
        assertThat(storage.graphStore().allRelations()).singleElement().satisfies(relation ->
            assertThat(relation.id()).isEqualTo(relationKey("Alice", "works_with", "Bob"))
        );
    }

    private static String entityKey(String name) {
        return "" + name.strip().toLowerCase(Locale.ROOT);
    }

    private static String relationKey(String source, String type, String target) {
        return RelationIds.relationId(entityKey(source), entityKey(target));
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {"entities":[{"name":"Alice","type":"person","description":"Alice","aliases":[]},{"name":"Bob","type":"person","description":"Bob","aliases":[]}],"relations":[{"source_entity":"Alice","target_entity":"Bob","relationship_keywords":"works_with","relationship_description":"works with","weight":1.0}]}
                """;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of((double) text.length(), 1.0d)).toList();
        }
    }

    private static final class BatchOnlyAtomicStorageProvider implements AtomicStorageProvider {
        private final InMemoryStorageProvider delegate = InMemoryStorageProvider.create();
        private final BatchOnlyGraphStore graphRecorder = new BatchOnlyGraphStore((InMemoryGraphStore) delegate.graphStore());

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
            return graphRecorder;
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
        public TaskStore taskStore() {
            return delegate.taskStore();
        }

        @Override
        public TaskStageStore taskStageStore() {
            return delegate.taskStageStore();
        }

        @Override
        public SnapshotStore snapshotStore() {
            return delegate.snapshotStore();
        }

        @Override
        public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
            return delegate.documentGraphSnapshotStore();
        }

        @Override
        public DocumentGraphJournalStore documentGraphJournalStore() {
            return delegate.documentGraphJournalStore();
        }

        @Override
        public <T> T writeAtomically(AtomicOperation<T> operation) {
            return delegate.writeAtomically(storage -> operation.execute(new AtomicStorageView() {
                @Override
                public DocumentStore documentStore() {
                    return storage.documentStore();
                }

                @Override
                public ChunkStore chunkStore() {
                    return storage.chunkStore();
                }

                @Override
                public DocumentGraphSnapshotStore documentGraphSnapshotStore() {
                    return storage.documentGraphSnapshotStore();
                }

                @Override
                public DocumentGraphJournalStore documentGraphJournalStore() {
                    return storage.documentGraphJournalStore();
                }

                @Override
                public GraphStore graphStore() {
                    return graphRecorder;
                }

                @Override
                public VectorStore vectorStore() {
                    return storage.vectorStore();
                }

                @Override
                public DocumentStatusStore documentStatusStore() {
                    return storage.documentStatusStore();
                }
            }));
        }

        @Override
        public void restore(SnapshotStore.Snapshot snapshot) {
            delegate.restore(snapshot);
        }
    }

    private static final class BatchOnlyGraphStore implements GraphStore {
        private final InMemoryGraphStore delegate;
        private final List<List<String>> loadedEntityIdBatches = new java.util.ArrayList<>();
        private final List<List<String>> loadedRelationIdBatches = new java.util.ArrayList<>();
        private final List<List<EntityRecord>> savedEntityBatches = new java.util.ArrayList<>();
        private final List<List<RelationRecord>> savedRelationBatches = new java.util.ArrayList<>();

        private BatchOnlyGraphStore(InMemoryGraphStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            throw new AssertionError("saveEntity should not be used");
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            throw new AssertionError("saveRelation should not be used");
        }

        @Override
        public void saveEntities(List<EntityRecord> entities) {
            savedEntityBatches.add(List.copyOf(entities));
            delegate.saveEntities(entities);
        }

        @Override
        public void saveRelations(List<RelationRecord> relations) {
            savedRelationBatches.add(List.copyOf(relations));
            delegate.saveRelations(relations);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            throw new AssertionError("loadEntity should not be used");
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            throw new AssertionError("loadRelation should not be used");
        }

        @Override
        public List<EntityRecord> loadEntities(List<String> entityIds) {
            loadedEntityIdBatches.add(List.copyOf(entityIds));
            return delegate.loadEntities(entityIds);
        }

        @Override
        public List<RelationRecord> loadRelations(List<String> relationIds) {
            loadedRelationIdBatches.add(List.copyOf(relationIds));
            return delegate.loadRelations(relationIds);
        }

        @Override
        public List<EntityRecord> allEntities() {
            return delegate.allEntities();
        }

        @Override
        public List<RelationRecord> allRelations() {
            return delegate.allRelations();
        }

        @Override
        public List<RelationRecord> findRelations(String entityId) {
            return delegate.findRelations(entityId);
        }
    }
}
