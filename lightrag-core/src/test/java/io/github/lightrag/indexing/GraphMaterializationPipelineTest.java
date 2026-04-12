package io.github.lightrag.indexing;

import io.github.lightrag.api.ChunkGraphStatus;
import io.github.lightrag.api.ChunkMergeStatus;
import io.github.lightrag.api.DocumentChunkGraphStatus;
import io.github.lightrag.api.FailureStage;
import io.github.lightrag.api.GraphChunkAction;
import io.github.lightrag.api.GraphMaterializationMode;
import io.github.lightrag.api.GraphMaterializationStatus;
import io.github.lightrag.api.LightRag;
import io.github.lightrag.model.ChatModel;
import io.github.lightrag.model.EmbeddingModel;
import io.github.lightrag.storage.DocumentGraphJournalStore;
import io.github.lightrag.storage.DocumentGraphSnapshotStore;
import io.github.lightrag.storage.InMemoryStorageProvider;
import io.github.lightrag.task.TaskMetadataReporter;
import io.github.lightrag.storage.memory.InMemoryGraphStore;
import io.github.lightrag.storage.memory.InMemoryVectorStore;
import io.github.lightrag.types.Document;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMaterializationPipelineTest {
    private static final String WORKSPACE = "default";

    @Test
    void ingestPersistsDurableDocumentGraphSnapshotAndJournalState() {
        var storage = InMemoryStorageProvider.create();
        try (var rag = newLightRag(storage)) {
            rag.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        }

        var documentSnapshot = storage.documentGraphSnapshotStore().loadDocument("doc-1");
        var chunkSnapshots = storage.documentGraphSnapshotStore().listChunks("doc-1");
        var documentJournals = storage.documentGraphJournalStore().listDocumentJournals("doc-1");
        var chunkJournals = storage.documentGraphJournalStore().listChunkJournals("doc-1");

        assertThat(documentSnapshot).isPresent();
        assertThat(documentSnapshot.orElseThrow().status()).isEqualTo(io.github.lightrag.api.SnapshotStatus.READY);
        assertThat(chunkSnapshots).hasSize(1);
        assertThat(chunkSnapshots.get(0).extractStatus()).isEqualTo(io.github.lightrag.api.ChunkExtractStatus.SUCCEEDED);
        assertThat(documentJournals).isNotEmpty();
        assertThat(documentJournals.get(documentJournals.size() - 1).status()).isEqualTo(GraphMaterializationStatus.MERGED);
        assertThat(chunkJournals).singleElement().satisfies(chunk -> {
            assertThat(chunk.mergeStatus()).isEqualTo(ChunkMergeStatus.SUCCEEDED);
            assertThat(chunk.graphStatus()).isEqualTo(ChunkGraphStatus.MATERIALIZED);
        });
    }

    @Test
    void inspectAndRepairChunkUsePersistedState() {
        var storage = InMemoryStorageProvider.create();
        try (var rag = newLightRag(storage)) {
            rag.ingest(WORKSPACE, List.of(new Document("doc-1", "Title", "Alice works with Bob", Map.of())));
        }
        degradeOnlyChunkMaterialization(storage, "doc-1", "doc-1:0");

        var pipeline = new GraphMaterializationPipeline(
            new FakeChatModel(),
            new FakeEmbeddingModel(),
            storage,
            io.github.lightrag.indexing.refinement.ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop()
        );

        var inspection = pipeline.inspect("doc-1");
        var beforeChunk = pipeline.getChunkStatus("doc-1", "doc-1:0");
        var repair = pipeline.repairChunk("doc-1", "doc-1:0");
        var afterChunk = pipeline.getChunkStatus("doc-1", "doc-1:0");

        assertThat(inspection.graphStatus()).isEqualTo(GraphMaterializationStatus.PARTIAL);
        assertThat(inspection.recommendedMode()).isEqualTo(GraphMaterializationMode.REPAIR);
        assertThat(beforeChunk.graphStatus()).isEqualTo(ChunkGraphStatus.PARTIAL);
        assertThat(beforeChunk.recommendedAction()).isEqualTo(GraphChunkAction.REPAIR);
        assertThat(repair.finalStatus()).isEqualTo(ChunkGraphStatus.MATERIALIZED);
        assertThat(afterChunk.graphStatus()).isEqualTo(ChunkGraphStatus.MATERIALIZED);
    }

    @Test
    void listChunkStatusesReturnsEveryChunkInDocumentOrder() {
        var storage = InMemoryStorageProvider.create();
        seedDocumentGraphState(storage, "doc-1", Instant.parse("2026-04-12T00:00:00Z"), List.of(
            chunkSnapshot("doc-1", "doc-1:0", 0, "Alice works with Bob"),
            chunkSnapshot("doc-1", "doc-1:1", 1, "Bob works with Carol")
        ));
        storage.documentGraphJournalStore().appendDocument(documentJournal("doc-1", GraphMaterializationStatus.PARTIAL, 4, 2, 2, 1));
        storage.documentGraphJournalStore().appendChunks("doc-1", List.of(
            chunkJournal("doc-1", "doc-1:0", 0, ChunkGraphStatus.MATERIALIZED, List.of(entityKey("Alice"), entityKey("Bob")), List.of(relationKey("Alice", "works_with", "Bob"))),
            chunkJournal("doc-1", "doc-1:1", 1, ChunkGraphStatus.PARTIAL, List.of(entityKey("Bob"), entityKey("Carol")), List.of())
        ));
        ((InMemoryGraphStore) storage.graphStore()).restore(
            List.of(
                new io.github.lightrag.storage.GraphStore.EntityRecord("entity:alice", "Alice", "person", "Alice", List.of(), List.of("doc-1:0")),
                new io.github.lightrag.storage.GraphStore.EntityRecord("entity:bob", "Bob", "person", "Bob", List.of(), List.of("doc-1:0", "doc-1:1")),
                new io.github.lightrag.storage.GraphStore.EntityRecord("entity:carol", "Carol", "person", "Carol", List.of(), List.of("doc-1:1"))
            ),
            List.of(
                new io.github.lightrag.storage.GraphStore.RelationRecord(
                    relationKey("Alice", "works_with", "Bob"),
                    entityKey("Alice"),
                    entityKey("Bob"),
                    "works_with",
                    "works with",
                    1.0d,
                    List.of("doc-1:0")
                )
            )
        );

        var pipeline = new GraphMaterializationPipeline(
            new FakeChatModel(),
            new FakeEmbeddingModel(),
            storage,
            io.github.lightrag.indexing.refinement.ExtractionRefinementOptions.disabled(),
            null,
            TaskMetadataReporter.noop(),
            IndexingProgressListener.noop()
        );

        List<DocumentChunkGraphStatus> statuses = pipeline.listChunkStatuses("doc-1");

        assertThat(statuses).extracting(DocumentChunkGraphStatus::chunkId)
            .containsExactly("doc-1:0", "doc-1:1");
        assertThat(statuses).extracting(DocumentChunkGraphStatus::graphStatus)
            .containsExactly(ChunkGraphStatus.MATERIALIZED, ChunkGraphStatus.PARTIAL);
    }

    private static LightRag newLightRag(InMemoryStorageProvider storage) {
        return LightRag.builder()
            .chatModel(new FakeChatModel())
            .embeddingModel(new FakeEmbeddingModel())
            .storage(storage)
            .build();
    }

    private static void degradeOnlyChunkMaterialization(InMemoryStorageProvider storage, String documentId, String chunkId) {
        storage.documentGraphJournalStore().appendDocument(documentJournal(documentId, GraphMaterializationStatus.PARTIAL, 2, 1, 1, 0));
        storage.documentGraphJournalStore().appendChunks(documentId, List.of(
            new DocumentGraphJournalStore.ChunkGraphJournal(
                documentId,
                chunkId,
                1,
                ChunkMergeStatus.FAILED,
                ChunkGraphStatus.PARTIAL,
                List.of(entityKey("Alice"), entityKey("Bob")),
                List.of(relationKey("Alice", "works_with", "Bob")),
                List.of(entityKey("Alice")),
                List.of(),
                FailureStage.RELATION_MATERIALIZATION,
                Instant.parse("2026-04-12T00:00:10Z"),
                "relation missing"
            )
        ));
        ((InMemoryGraphStore) storage.graphStore()).restore(
            List.of(((InMemoryGraphStore) storage.graphStore()).loadEntity("entity:alice").orElseThrow()),
            List.of()
        );
        ((InMemoryVectorStore) storage.vectorStore()).restore(Map.of(
            StorageSnapshots.ENTITY_NAMESPACE,
            List.of(((InMemoryVectorStore) storage.vectorStore()).list(StorageSnapshots.ENTITY_NAMESPACE).stream()
                .filter(vector -> vector.id().equals("entity:alice"))
                .findFirst()
                .orElseThrow())
        ));
    }

    private static void seedDocumentGraphState(
        InMemoryStorageProvider storage,
        String documentId,
        Instant now,
        List<DocumentGraphSnapshotStore.ChunkGraphSnapshot> chunks
    ) {
        storage.documentGraphSnapshotStore().saveDocument(new DocumentGraphSnapshotStore.DocumentGraphSnapshot(
            documentId,
            1,
            io.github.lightrag.api.SnapshotStatus.READY,
            io.github.lightrag.api.SnapshotSource.PRIMARY_EXTRACTION,
            chunks.size(),
            now,
            now,
            null
        ));
        storage.documentGraphSnapshotStore().saveChunks(documentId, chunks);
    }

    private static DocumentGraphSnapshotStore.ChunkGraphSnapshot chunkSnapshot(
        String documentId,
        String chunkId,
        int order,
        String relationText
    ) {
        String[] parts = relationText.split(" works with ");
        var source = parts[0];
        var target = parts[1];
        return new DocumentGraphSnapshotStore.ChunkGraphSnapshot(
            documentId,
            chunkId,
            order,
            "hash-" + chunkId,
            io.github.lightrag.api.ChunkExtractStatus.SUCCEEDED,
            List.of(
                entityRecord(source),
                entityRecord(target)
            ),
            List.of(
                relationRecord(source, target)
            ),
            Instant.parse("2026-04-12T00:00:00Z"),
            null
        );
    }

    private static DocumentGraphSnapshotStore.ExtractedEntityRecord entityRecord(String name) {
        return new DocumentGraphSnapshotStore.ExtractedEntityRecord(name, "person", name, List.of());
    }

    private static DocumentGraphSnapshotStore.ExtractedRelationRecord relationRecord(String source, String target) {
        return new DocumentGraphSnapshotStore.ExtractedRelationRecord(source, target, "works_with", "works with", 1.0d);
    }

    private static DocumentGraphJournalStore.DocumentGraphJournal documentJournal(
        String documentId,
        GraphMaterializationStatus status,
        int expectedEntityCount,
        int expectedRelationCount,
        int materializedEntityCount,
        int materializedRelationCount
    ) {
        var now = Instant.parse("2026-04-12T00:00:00Z");
        return new DocumentGraphJournalStore.DocumentGraphJournal(
            documentId,
            1,
            status,
            GraphMaterializationMode.AUTO,
            expectedEntityCount,
            expectedRelationCount,
            materializedEntityCount,
            materializedRelationCount,
            FailureStage.FINALIZING,
            now,
            now,
            null
        );
    }

    private static DocumentGraphJournalStore.ChunkGraphJournal chunkJournal(
        String documentId,
        String chunkId,
        int order,
        ChunkGraphStatus graphStatus,
        List<String> entityKeys,
        List<String> relationKeys
    ) {
        return new DocumentGraphJournalStore.ChunkGraphJournal(
            documentId,
            chunkId,
            1,
            graphStatus == ChunkGraphStatus.MATERIALIZED ? ChunkMergeStatus.SUCCEEDED : ChunkMergeStatus.FAILED,
            graphStatus,
            entityKeys,
            relationKeys,
            graphStatus == ChunkGraphStatus.MATERIALIZED ? entityKeys : entityKeys.subList(0, Math.min(1, entityKeys.size())),
            graphStatus == ChunkGraphStatus.MATERIALIZED ? relationKeys : List.of(),
            graphStatus == ChunkGraphStatus.MATERIALIZED ? FailureStage.FINALIZING : FailureStage.RELATION_MATERIALIZATION,
            Instant.parse("2026-04-12T00:00:00Z").plusSeconds(order),
            graphStatus == ChunkGraphStatus.MATERIALIZED ? null : "relation missing"
        );
    }

    private static String entityKey(String name) {
        return "entity:" + name.strip().toLowerCase(Locale.ROOT);
    }

    private static String relationKey(String source, String type, String target) {
        return "relation:" + entityKey(source) + "|" + type.toLowerCase(Locale.ROOT) + "|" + entityKey(target);
    }

    private static final class FakeChatModel implements ChatModel {
        @Override
        public String generate(ChatRequest request) {
            return """
                {"entities":[{"name":"Alice","type":"person","description":"Alice","aliases":[]},{"name":"Bob","type":"person","description":"Bob","aliases":[]}],"relations":[{"sourceEntityName":"Alice","targetEntityName":"Bob","type":"works_with","description":"works with","weight":1.0}]}
                """;
        }
    }

    private static final class FakeEmbeddingModel implements EmbeddingModel {
        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            return texts.stream().map(text -> List.of((double) text.length(), 1.0d)).toList();
        }
    }
}
