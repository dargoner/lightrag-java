package io.github.lightrag.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageAssemblyTest {
    @Test
    void buildsAtomicStorageProviderFromInjectedAdapters() {
        var relational = new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter();
        var graph = new StorageAssemblyTestDoubles.FakeGraphStorageAdapter();
        var vector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();

        var provider = StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(vector)
            .build()
            .toStorageProvider();

        assertThat(provider).isInstanceOf(AtomicStorageProvider.class);
        assertThat(provider.documentStore()).isSameAs(relational.documentStore());
        assertThat(provider.chunkStore()).isSameAs(relational.chunkStore());
        assertThat(provider.documentStatusStore()).isSameAs(relational.documentStatusStore());
        assertThat(provider.graphStore()).isSameAs(graph.graphStore());
        assertThat(provider.vectorStore()).isSameAs(vector.vectorStore());
    }

    @Test
    void restoresRelationalGraphAndVectorSnapshotsWhenGraphProjectionFails() {
        var relational = new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter();
        var graph = new StorageAssemblyTestDoubles.FakeGraphStorageAdapter();
        var vector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();
        var provider = StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(vector)
            .build()
            .toStorageProvider();

        var seedDocument = new DocumentStore.DocumentRecord("doc-0", "seed", "seed", Map.of("seed", "true"));
        var seedChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        var seedEntity = new GraphStore.EntityRecord(
            "entity-0",
            "seed",
            "seed",
            "seed",
            List.of(),
            List.of("doc-0:0")
        );
        var seedRelation = new GraphStore.RelationRecord(
            "relation-0",
            "entity-0",
            "entity-0",
            "seed",
            "seed",
            1.0d,
            List.of("doc-0:0")
        );
        var seedVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d));
        relational.documentStore().save(seedDocument);
        relational.chunkStore().save(seedChunk);
        graph.graphStore().saveEntity(seedEntity);
        graph.graphStore().saveRelation(seedRelation);
        vector.vectorStore().saveAll("chunks", List.of(seedVector));

        var graphFailure = new IllegalStateException("graph-apply-failed");
        graph.failOnApply(graphFailure);

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "incoming", "body", Map.of()));
            storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
            storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                "entity-1",
                "incoming",
                "incoming",
                "incoming",
                List.of(),
                List.of("doc-1:0")
            ));
            storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-0",
                "links_to",
                "incoming",
                0.5d,
                List.of("doc-1:0")
            ));
            storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d))));
            return null;
        }))
            .isSameAs(graphFailure);

        assertThat(relational.restoreCount()).isEqualTo(1);
        assertThat(graph.restoreCount()).isEqualTo(1);
        assertThat(vector.restoreCount()).isEqualTo(1);
        assertThat(graph.applyCount()).isEqualTo(1);
        assertThat(vector.applyCount()).isZero();

        assertThat(relational.documentStore().list()).containsExactly(seedDocument);
        assertThat(relational.chunkStore().list()).containsExactly(seedChunk);
        assertThat(graph.graphStore().allEntities()).containsExactly(seedEntity);
        assertThat(graph.graphStore().allRelations()).containsExactly(seedRelation);
        assertThat(vector.vectorStore().list("chunks")).containsExactly(seedVector);
    }

    @Test
    void restoresRelationalGraphAndVectorSnapshotsWhenVectorProjectionFailsAfterGraphApply() {
        var relational = new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter();
        var graph = new StorageAssemblyTestDoubles.FakeGraphStorageAdapter();
        var vector = new StorageAssemblyTestDoubles.FakeVectorStorageAdapter();
        var provider = StorageAssembly.builder()
            .relationalAdapter(relational)
            .graphAdapter(graph)
            .vectorAdapter(vector)
            .build()
            .toStorageProvider();

        var seedDocument = new DocumentStore.DocumentRecord("doc-0", "seed", "seed", Map.of("seed", "true"));
        var seedChunk = new ChunkStore.ChunkRecord("doc-0:0", "doc-0", "seed", 4, 0, Map.of("seed", "true"));
        var seedEntity = new GraphStore.EntityRecord(
            "entity-0",
            "seed",
            "seed",
            "seed",
            List.of(),
            List.of("doc-0:0")
        );
        var seedRelation = new GraphStore.RelationRecord(
            "relation-0",
            "entity-0",
            "entity-0",
            "seed",
            "seed",
            1.0d,
            List.of("doc-0:0")
        );
        var seedVector = new VectorStore.VectorRecord("doc-0:0", List.of(1.0d, 0.0d));
        relational.documentStore().save(seedDocument);
        relational.chunkStore().save(seedChunk);
        graph.graphStore().saveEntity(seedEntity);
        graph.graphStore().saveRelation(seedRelation);
        vector.vectorStore().saveAll("chunks", List.of(seedVector));

        var vectorFailure = new IllegalStateException("vector-apply-failed");
        vector.failOnApply(vectorFailure);

        assertThatThrownBy(() -> provider.writeAtomically(storage -> {
            storage.documentStore().save(new DocumentStore.DocumentRecord("doc-1", "incoming", "body", Map.of()));
            storage.chunkStore().save(new ChunkStore.ChunkRecord("doc-1:0", "doc-1", "body", 4, 0, Map.of()));
            storage.graphStore().saveEntity(new GraphStore.EntityRecord(
                "entity-1",
                "incoming",
                "incoming",
                "incoming",
                List.of(),
                List.of("doc-1:0")
            ));
            storage.graphStore().saveRelation(new GraphStore.RelationRecord(
                "relation-1",
                "entity-1",
                "entity-0",
                "links_to",
                "incoming",
                0.5d,
                List.of("doc-1:0")
            ));
            storage.vectorStore().saveAll("chunks", List.of(new VectorStore.VectorRecord("doc-1:0", List.of(0.5d, 0.5d))));
            return null;
        }))
            .isSameAs(vectorFailure);

        assertThat(relational.restoreCount()).isEqualTo(1);
        assertThat(graph.restoreCount()).isEqualTo(1);
        assertThat(vector.restoreCount()).isEqualTo(1);
        assertThat(graph.applyCount()).isEqualTo(1);
        assertThat(vector.applyCount()).isEqualTo(1);

        assertThat(relational.documentStore().list()).containsExactly(seedDocument);
        assertThat(relational.chunkStore().list()).containsExactly(seedChunk);
        assertThat(graph.graphStore().allEntities()).containsExactly(seedEntity);
        assertThat(graph.graphStore().allRelations()).containsExactly(seedRelation);
        assertThat(vector.vectorStore().list("chunks")).containsExactly(seedVector);
    }

    @Test
    void preservesHybridVectorPayloadsAcrossAtomicWrites() {
        var provider = StorageAssembly.builder()
            .relationalAdapter(new StorageAssemblyTestDoubles.FakeRelationalStorageAdapter())
            .graphAdapter(new StorageAssemblyTestDoubles.FakeGraphStorageAdapter())
            .vectorAdapter(new StorageAssemblyTestDoubles.FakeVectorStorageAdapter())
            .build()
            .toStorageProvider();

        provider.writeAtomically(storage -> {
            assertThat(storage.vectorStore()).isInstanceOf(HybridVectorStore.class);
            ((HybridVectorStore) storage.vectorStore()).saveAllEnriched(
                "chunks",
                List.of(new HybridVectorStore.EnrichedVectorRecord(
                    "doc-1:0",
                    List.of(1.0d, 0.0d),
                    "alpha beta",
                    List.of("alpha", "beta")
                ))
            );
            return null;
        });

        assertThat(provider.vectorStore()).isInstanceOf(HybridVectorStore.class);
        assertThat(((HybridVectorStore) provider.vectorStore()).search(
            "chunks",
            new HybridVectorStore.SearchRequest(
                List.of(1.0d, 0.0d),
                "beta",
                List.of("alpha"),
                HybridVectorStore.SearchMode.HYBRID,
                1
            )
        )).extracting(VectorStore.VectorMatch::id)
            .containsExactly("doc-1:0");
    }
}
