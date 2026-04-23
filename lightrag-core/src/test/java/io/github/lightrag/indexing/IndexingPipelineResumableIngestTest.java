package io.github.lightrag.indexing;

import io.github.lightrag.api.DocumentStatus;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.TaskStage;
import io.github.lightrag.indexing.refinement.ExtractionRefinementOptions;
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
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.types.Chunk;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingPipelineResumableIngestTest {
    @Test
    void persistsDocumentChunksAndChunkVectorsBeforePrimaryExtractionStarts() {
        var storage = InMemoryStorageProvider.create();
        var listener = new IndexingProgressListener() {
            @Override
            public void onStageStarted(TaskStage stage, String message) {
                if (stage != TaskStage.PRIMARY_EXTRACTION) {
                    return;
                }
                assertThat(storage.documentStore().load("doc-1")).isPresent();
                assertThat(storage.chunkStore().listByDocument("doc-1"))
                    .extracting(ChunkStore.ChunkRecord::id)
                    .containsExactly("doc-1:0");
                assertThat(storage.documentStatusStore().load("doc-1"))
                    .contains(new DocumentStatusStore.StatusRecord("doc-1", DocumentStatus.PROCESSING, "", null));
                assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
                    .extracting(VectorStore.VectorRecord::id)
                    .containsExactly("doc-1:0");
            }
        };
        var pipeline = newPipeline(storage, listener);

        pipeline.ingest(List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
    }

    @Test
    void preservesGraphSnapshotsWhenInitialMaterializationFailsSoDocumentCanBeResumed() {
        var storage = new FailingFirstGraphWriteStorageProvider();
        var pipeline = newPipeline(storage, IndexingProgressListener.noop());

        assertThatThrownBy(() -> pipeline.ingest(List.of(
            new Document("doc-1", "Title", "Alice works with Bob", Map.of())
        )))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("synthetic graph write failure");

        assertThat(storage.chunkStore().listByDocument("doc-1"))
            .extracting(ChunkStore.ChunkRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.vectorStore().list(StorageSnapshots.CHUNK_NAMESPACE))
            .extracting(VectorStore.VectorRecord::id)
            .containsExactly("doc-1:0");
        assertThat(storage.documentGraphSnapshotStore().loadDocument("doc-1")).isPresent();
        assertThat(storage.documentGraphSnapshotStore().listChunks("doc-1"))
            .extracting(DocumentGraphSnapshotStore.ChunkGraphSnapshot::chunkId)
            .containsExactly("doc-1:0");

        var materialization = new GraphMaterializationPipeline(
            new FakeChatModel(),
            new FakeEmbeddingModel(),
            storage,
            ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop()
        ).materialize("doc-1", GraphMaterializationMode.AUTO);

        assertThat(materialization.finalStatus()).isEqualTo(GraphMaterializationStatus.MERGED);
        assertThat(storage.graphStore().allEntities()).hasSize(2);
        assertThat(storage.graphStore().allRelations()).hasSize(1);
    }

    private static IndexingPipeline newPipeline(AtomicStorageProvider storage, IndexingProgressListener listener) {
        return new IndexingPipeline(
            new FakeChatModel(),
            new FakeChatModel(),
            new FakeEmbeddingModel(),
            storage,
            null,
            document -> List.of(new Chunk(document.id() + ":0", document.id(), document.content(), 4, 0, Map.of())),
            null,
            Integer.MAX_VALUE,
            1,
            1,
            0,
            KnowledgeExtractor.DEFAULT_MAX_EXTRACT_INPUT_TOKENS,
            KnowledgeExtractor.DEFAULT_LANGUAGE,
            KnowledgeExtractor.DEFAULT_ENTITY_TYPES,
            false,
            0.80d,
            ExtractionRefinementOptions.disabled(),
            listener
        );
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

    private static final class FailingFirstGraphWriteStorageProvider implements AtomicStorageProvider {
        private final InMemoryStorageProvider delegate = InMemoryStorageProvider.create();
        private final FailingFirstGraphStore graphStore = new FailingFirstGraphStore((InMemoryGraphStore) delegate.graphStore());

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
            return graphStore;
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
                    return graphStore;
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

    private static final class FailingFirstGraphStore implements GraphStore {
        private final InMemoryGraphStore delegate;
        private final AtomicBoolean firstFailure = new AtomicBoolean(true);

        private FailingFirstGraphStore(InMemoryGraphStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void saveEntity(EntityRecord entity) {
            failIfNeeded();
            delegate.saveEntity(entity);
        }

        @Override
        public void saveRelation(RelationRecord relation) {
            failIfNeeded();
            delegate.saveRelation(relation);
        }

        @Override
        public void saveEntities(List<EntityRecord> entities) {
            failIfNeeded();
            delegate.saveEntities(entities);
        }

        @Override
        public void saveRelations(List<RelationRecord> relations) {
            failIfNeeded();
            delegate.saveRelations(relations);
        }

        @Override
        public java.util.Optional<EntityRecord> loadEntity(String entityId) {
            return delegate.loadEntity(entityId);
        }

        @Override
        public java.util.Optional<RelationRecord> loadRelation(String relationId) {
            return delegate.loadRelation(relationId);
        }

        @Override
        public List<EntityRecord> loadEntities(List<String> entityIds) {
            return delegate.loadEntities(entityIds);
        }

        @Override
        public List<RelationRecord> loadRelations(List<String> relationIds) {
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

        private void failIfNeeded() {
            if (firstFailure.compareAndSet(true, false)) {
                throw new IllegalStateException("synthetic graph write failure");
            }
        }
    }
}
